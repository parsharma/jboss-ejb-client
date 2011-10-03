/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client.remoting;

import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverContext;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.logging.Logger;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.Connection;
import org.xnio.IoFuture;
import org.xnio.OptionMap;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A {@link EJBReceiver} which uses JBoss Remoting to communicate with the server for EJB invocations
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class RemotingConnectionEJBReceiver extends EJBReceiver<RemotingAttachments> {

    private static final Logger logger = Logger.getLogger(RemotingConnectionEJBReceiver.class);

    private static final String EJB_CHANNEL_NAME = "jboss.ejb";

    private final Connection connection;

    private final Map<EJBReceiverContext, ChannelAssociation> channelAssociations = new IdentityHashMap<EJBReceiverContext, ChannelAssociation>();

    // TODO: The version and the marshalling strategy shouldn't be hardcoded here
    private final byte clientProtocolVersion = 0x01;
    private final String clientMarshallingStrategy = "river";

    /**
     * Construct a new instance.
     *
     * @param connection the connection to associate with
     */
    public RemotingConnectionEJBReceiver(final Connection connection) {
        this.connection = connection;
    }

    @Override
    public void associate(final EJBReceiverContext context) {
        final CountDownLatch versionHandshakeLatch = new CountDownLatch(1);
        final VersionReceiver versionReceiver = new VersionReceiver(versionHandshakeLatch, this.clientProtocolVersion, this.clientMarshallingStrategy);
        final IoFuture<Channel> futureChannel = connection.openChannel(EJB_CHANNEL_NAME, OptionMap.EMPTY);
        futureChannel.addNotifier(new IoFuture.HandlingNotifier<Channel, EJBReceiverContext>() {
            public void handleCancelled(final EJBReceiverContext context) {
                context.close();
            }

            public void handleFailed(final IOException exception, final EJBReceiverContext context) {
                // todo: log?
                context.close();
            }

            public void handleDone(final Channel channel, final EJBReceiverContext context) {
                channel.addCloseHandler(new CloseHandler<Channel>() {
                    public void handleClose(final Channel closed, final IOException exception) {
                        context.close();
                    }
                });
                // receive version message from server
                channel.receiveMessage(versionReceiver);
            }
        }, context);

        try {
            // wait for the handshake to complete
            // TODO: Think about externalizing this timeout
            final boolean successfulHandshake = versionHandshakeLatch.await(5, TimeUnit.SECONDS);
            if (successfulHandshake) {
                final Channel compatibleChannel = versionReceiver.getCompatibleChannel();
                final ChannelAssociation channelAssociation = new ChannelAssociation(this, context, compatibleChannel, this.clientProtocolVersion, this.clientMarshallingStrategy);
                synchronized (this.channelAssociations) {
                    this.channelAssociations.put(context, channelAssociation);
                }
                logger.info("Successful version handshake completed for receiver context " + context + " on channel " + compatibleChannel);
            } else {
                // no version handshake done. close the context
                logger.info("Version handshake not completed for recevier context " + context + " by receiver " + this + " . Closing the receiver context");
                context.close();
            }
        } catch (InterruptedException e) {
            context.close();
        }
    }

    @Override
    public void processInvocation(final EJBClientInvocationContext<RemotingAttachments> clientInvocationContext, final EJBReceiverInvocationContext ejbReceiverInvocationContext) throws Exception {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(ejbReceiverInvocationContext.getEjbReceiverContext());
        final Channel channel = channelAssociation.getChannel();
        final MethodInvocationMessageWriter messageWriter = new MethodInvocationMessageWriter(this.clientProtocolVersion, this.clientMarshallingStrategy);
        final DataOutputStream dataOutputStream = new DataOutputStream(channel.writeMessage());
        final short invocationId = channelAssociation.getNextInvocationId();
        try {
            messageWriter.writeMessage(dataOutputStream, invocationId, clientInvocationContext);
        } finally {
            dataOutputStream.close();
        }
        channelAssociation.receiveResponse(invocationId, ejbReceiverInvocationContext);
    }

    @Override
    public SessionID openSession(final EJBReceiverContext receiverContext, final String appName, final String moduleName, final String distinctName, final String beanName) throws Exception {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final Channel channel = channelAssociation.getChannel();
        final SessionOpenRequestWriter sessionOpenRequestWriter = new SessionOpenRequestWriter(this.clientProtocolVersion, this.clientMarshallingStrategy);
        final DataOutputStream dataOutputStream = new DataOutputStream(channel.writeMessage());
        final short invocationId = channelAssociation.getNextInvocationId();
        try {
            // TODO: Do we need to send along some attachments?
            sessionOpenRequestWriter.writeMessage(dataOutputStream, invocationId, appName, moduleName, distinctName, beanName, null);
        } finally {
            dataOutputStream.close();
        }
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.receiveResponse(invocationId);
        final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
        final SessionID sessionId = (SessionID) resultProducer.getResult();
        return sessionId;
    }

    public void verify(final String appName, final String moduleName, final String distinctName, final String beanName) throws Exception {
        // TODO: Implement
        logger.warn("Not yet implemented RemotingConnectionEJBReceiver#verify");
    }

    public RemotingAttachments createReceiverSpecific() {
        return new RemotingAttachments();
    }

    void moduleAvailable(final String appName, final String moduleName, final String distinctName) {
        logger.debug("Received module availability message for appName: " + appName + " moduleName: " + moduleName + " distinctName: " + distinctName);
        this.registerModule(appName, moduleName, distinctName);
    }

    void moduleUnavailable(final String appName, final String moduleName, final String distinctName) {
        logger.debug("Received module un-availability message for appName: " + appName + " moduleName: " + moduleName + " distinctName: " + distinctName);
        this.deRegisterModule(appName, moduleName, distinctName);
    }

    private ChannelAssociation requireChannelAssociation(final EJBReceiverContext ejbReceiverContext) {
        ChannelAssociation channelAssociation;
        synchronized (this.channelAssociations) {
            channelAssociation = this.channelAssociations.get(ejbReceiverContext);
        }
        if (channelAssociation == null) {
            throw new IllegalStateException("EJB receiver " + this + " is not yet ready to process invocations for receiver context " + ejbReceiverContext);
        }
        return channelAssociation;
    }

}
