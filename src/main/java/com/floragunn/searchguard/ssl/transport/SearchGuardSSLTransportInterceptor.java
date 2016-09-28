/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.ssl.transport;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.auth.x500.X500Principal;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.DelegatingTransportChannel;
import org.elasticsearch.transport.TcpTransportChannel;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportInterceptor;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;

import com.floragunn.searchguard.ssl.SearchGuardSSLPlugin.Holder;

public class SearchGuardSSLTransportInterceptor implements TransportInterceptor {
    
    private final Holder<ThreadPool> threadPoolHolder;
    
    public SearchGuardSSLTransportInterceptor(final Settings settings, final  Holder<ThreadPool> threadPoolHolder) {
        this.threadPoolHolder = threadPoolHolder;
    }
    
    
    @Override
    public <T extends TransportRequest> TransportRequestHandler<T> interceptHandler(String action,
            TransportRequestHandler<T> actualHandler) {
        return new SearchGuardRequestHandler<T>(action, actualHandler, threadPoolHolder);
    }

    @Override
    public AsyncSender interceptSender(AsyncSender sender) {
        return sender;
        
        /*return new TransportInterceptor.AsyncSender(){

            @Override
            public <T extends TransportResponse> void sendRequest(DiscoveryNode node, String action, TransportRequest request,
                    TransportRequestOptions options, TransportResponseHandler<T> handler) {
                
            }
            
        };*/
    }
    
    //static
    private class SearchGuardRequestHandler<T extends TransportRequest>
    implements TransportRequestHandler<T> {
        
        private final String action;
        private final TransportRequestHandler<T> actualHandler;
        private final Holder<ThreadPool> threadContextHolder;

        public SearchGuardRequestHandler(String action, TransportRequestHandler<T> actualHandler, Holder<ThreadPool> threadContextHolder) {
            super();
            this.action = action;
            this.actualHandler = actualHandler;
            this.threadContextHolder = threadContextHolder;
        }

        @Override
        public void messageReceived(T request, TransportChannel channel) throws Exception {
            messageReceived(request, channel, null);
        }

        @Override
        public void messageReceived(T request, TransportChannel channel, Task task) throws Exception {
          ThreadContext threadContext = threadContextHolder.getValue().getThreadContext();  
          
          //TODO 5.0 - check headers
           //HeaderHelper.checkSGHeader(request);
            
            if(channel.getChannelType().contains("flora") || "netty3".equals(channel.getChannelType()) || "netty4".equals(channel.getChannelType())) {
                final Exception exception = new ElasticsearchException(channel.getChannelType()+" not supported");
                channel.sendResponse(exception);
                throw exception;
            }
            
            if (!"netty".equals(channel.getChannelType())) { //netty4
                messageReceivedDecorate(request, actualHandler, channel, task);
                return;
            }
            
            try {
                
                Channel nettyChannel = null;
                
                if(channel instanceof DelegatingTransportChannel) {
                    nettyChannel = (Channel) ((TcpTransportChannel)((DelegatingTransportChannel) channel).getChannel()).getChannel();
                } else if (channel instanceof TcpTransportChannel) {
                    nettyChannel = (Channel) ((TcpTransportChannel) channel).getChannel();
                } else {
                    System.out.println("INVALID "+channel.getClass());
                }
                
                final SslHandler sslhandler = (SslHandler) nettyChannel.pipeline().get("ssl_server");

                if (sslhandler == null) {
                    final String msg = "No ssl handler found (SG 11)";
                    //log.error(msg);
                    final Exception exception = new ElasticsearchException(msg);
                    channel.sendResponse(exception);
                    throw exception;
                }
                
                
                X500Principal principal;

                final Certificate[] certs = sslhandler.engine().getSession().getPeerCertificates();
                
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    X509Certificate[] x509Certs = Arrays.copyOf(certs, certs.length, X509Certificate[].class);
                    addAdditionalContextValues(action, request, x509Certs);
                    principal = x509Certs[0].getSubjectX500Principal();
                    threadContext.putTransient("_sg_ssl_transport_principal", principal == null ? null : principal.getName());
                    threadContext.putTransient("_sg_ssl_transport_peer_certificates", x509Certs);
                    threadContext.putTransient("_sg_ssl_transport_protocol", sslhandler.engine().getSession().getProtocol());
                    threadContext.putTransient("_sg_ssl_transport_cipher", sslhandler.engine().getSession().getCipherSuite());
                    messageReceivedDecorate(request, actualHandler, channel, task);
                } else {
                    final String msg = "No X509 transport client certificates found (SG 12)";
                    //log.error(msg);
                    final Exception exception = new ElasticsearchException(msg);
                    errorThrown(exception, request, action);
                    channel.sendResponse(exception);
                    throw exception;
                }

            } catch (final SSLPeerUnverifiedException e) {
                //log.error("Can not verify SSL peer (SG 13) due to {}", e, e);
                errorThrown(e, request, action);
                final Exception exception = ExceptionsHelper.convertToElastic(e);
                channel.sendResponse(exception);
                throw exception;
            } catch (final Exception e) {
                //log.debug("Unexpected but unproblematic exception (SG 14) for '{}' due to {}", action, e.getMessage());
                //if(log.isTraceEnabled()) {
                //    log.trace("Unexpected but unproblematic exception (SG 14) for '{}' due to {}", e, action, e.getMessage());
                //}
                errorThrown(e, request, action);
                //final Exception exception = ExceptionsHelper.convertToElastic(e);
                //nettyChannel.sendResponse(exception);
                throw e;
            }
            
        }
        
    }

    protected void addAdditionalContextValues(final String action, final TransportRequest request, final X509Certificate[] certs)
            throws Exception {
        // no-op
    }
    
    protected void messageReceivedDecorate(final TransportRequest request, final TransportRequestHandler actualHandler, final TransportChannel transportChannel, Task task) throws Exception {
        actualHandler.messageReceived(request, transportChannel, task);
    }
    
    protected void errorThrown(Throwable t, final TransportRequest request, String action) {
        // no-op
    }
}
