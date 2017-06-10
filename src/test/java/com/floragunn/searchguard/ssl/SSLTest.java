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

package com.floragunn.searchguard.ssl;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.KeyStore;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.NoHttpResponseException;
import org.apache.lucene.util.Constants;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.PluginAwareNode;
import org.elasticsearch.transport.Netty4Plugin;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.floragunn.searchguard.ssl.util.SSLConfigConstants;

public class SSLTest extends AbstractUnitTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    protected boolean allowOpenSSL = false;
    
    @Test
    public void testHttps() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .putArray(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLED_PROTOCOLS, "TLSv1.1","TLSv1.2")
                .putArray(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLED_CIPHERS, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")
                .putArray(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLED_PROTOCOLS, "TLSv1.1","TLSv1.2")
                .putArray(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLED_CIPHERS, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .build();

        startES(settings);

        System.out.println(executeSimpleRequest("_searchguard/sslinfo?pretty"));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("\"searchguard\""));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("keystore_filepath"));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));

    }
    
    @Test
    public void testCipherAndProtocols() throws Exception {
        
        Security.setProperty("jdk.tls.disabledAlgorithms","");
        System.out.println("Disabled algos: "+Security.getProperty("jdk.tls.disabledAlgorithms"));
        System.out.println("allowOpenSSL: "+allowOpenSSL);

        Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                 //WEAK and insecure cipher, do NOT use this, its here for unittesting only!!!
                .put("searchguard.ssl.http.enabled_ciphers","SSL_RSA_EXPORT_WITH_RC4_40_MD5")
                 //WEAK and insecure protocol, do NOT use this, its here for unittesting only!!!
                .put("searchguard.ssl.http.enabled_protocols","SSLv3")
                .put("client.type","node")
                .put("path.home",".")
                .build();
        
        try {
            String[] enabledCiphers = new DefaultSearchGuardKeyStore(settings).createHTTPSSLEngine().getEnabledCipherSuites();
            String[] enabledProtocols = new DefaultSearchGuardKeyStore(settings).createHTTPSSLEngine().getEnabledProtocols();

            if(allowOpenSSL) {
                Assert.assertEquals(2, enabledProtocols.length); //SSLv2Hello is always enabled when using openssl
                Assert.assertTrue("Check SSLv3", "SSLv3".equals(enabledProtocols[0]) || "SSLv3".equals(enabledProtocols[1]));
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("TLS_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            } else {
                Assert.assertEquals(1, enabledProtocols.length);
                Assert.assertEquals("SSLv3", enabledProtocols[0]);
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            }
            
            settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                    .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                     //WEAK and insecure cipher, do NOT use this, its here for unittesting only!!!
                    .put("searchguard.ssl.transport.enabled_ciphers","SSL_RSA_EXPORT_WITH_RC4_40_MD5")
                     //WEAK and insecure protocol, do NOT use this, its here for unittesting only!!!
                    .put("searchguard.ssl.transport.enabled_protocols","SSLv3")
                    .put("client.type","node")
                    .put("path.home",".")
                    .build();
            
            enabledCiphers = new DefaultSearchGuardKeyStore(settings).createServerTransportSSLEngine().getEnabledCipherSuites();
            enabledProtocols = new DefaultSearchGuardKeyStore(settings).createServerTransportSSLEngine().getEnabledProtocols();

            if(allowOpenSSL) {
                Assert.assertEquals(2, enabledProtocols.length); //SSLv2Hello is always enabled when using openssl
                Assert.assertTrue("Check SSLv3", "SSLv3".equals(enabledProtocols[0]) || "SSLv3".equals(enabledProtocols[1]));
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("TLS_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            } else {
                Assert.assertEquals(1, enabledProtocols.length);
                Assert.assertEquals("SSLv3", enabledProtocols[0]);
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            }
            enabledCiphers = new DefaultSearchGuardKeyStore(settings).createClientTransportSSLEngine(null, -1).getEnabledCipherSuites();
            enabledProtocols = new DefaultSearchGuardKeyStore(settings).createClientTransportSSLEngine(null, -1).getEnabledProtocols();

            if(allowOpenSSL) {
                Assert.assertEquals(2, enabledProtocols.length); //SSLv2Hello is always enabled when using openssl
                Assert.assertTrue("Check SSLv3","SSLv3".equals(enabledProtocols[0]) || "SSLv3".equals(enabledProtocols[1]));
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("TLS_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            } else {
                Assert.assertEquals(1, enabledProtocols.length);
                Assert.assertEquals("SSLv3", enabledProtocols[0]);
                Assert.assertEquals(1, enabledCiphers.length);
                Assert.assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5",enabledCiphers[0]);
            }
        } catch (ElasticsearchSecurityException e) {
            System.out.println("EXPECTED "+e.getClass().getSimpleName()+" for "+System.getProperty("java.specification.version")+": "+e.toString());
            e.printStackTrace();
            Assert.assertTrue("Check if error contains 'no valid cipher suites' -> "+e.toString(),e.toString().contains("no valid cipher suites")
                    || e.toString().contains("failed to set cipher suite")
                    || e.toString().contains("Unable to configure permitted SSL ciphers")
                    || e.toString().contains("OPENSSL_internal:NO_CIPHER_MATCH")
                   );
            Assert.assertTrue("Check if >= Java 8 and no openssl",allowOpenSSL?true:Constants.JRE_IS_MINIMUM_JAVA8 );        
        }
    }
    
    @Test
    public void testHttpsOptionalAuth() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);

        System.out.println(executeSimpleRequest("_searchguard/sslinfo?pretty"));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("\"searchguard\""));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }
    
    @Test
    public void testHttpsAndNodeSSL() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)

                .put("searchguard.ssl.http.enabled", true).put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                
                .build();

        startES(settings);
        System.out.println(executeSimpleRequest("_searchguard/sslinfo?pretty"));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        //Assert.assertTrue(!executeSimpleRequest("_searchguard/sslinfo?pretty").contains("null"));
        //Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }
    
    @Test
    public void testHttpsAndNodeSSLPem() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)

                .put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .build();

        startES(settings);
        System.out.println(executeSimpleRequest("_searchguard/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertTrue(!executeSimpleRequest("_searchguard/sslinfo?pretty").contains("null"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }

    @Test
    public void testHttpsAndNodeSSLPemEnc() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.crt.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.key"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)

                .put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.crt.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("pem/node-4.key"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .build();

        startES(settings);
        System.out.println(executeSimpleRequest("_searchguard/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertTrue(!executeSimpleRequest("_searchguard/sslinfo?pretty").contains("null"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }

    
    @Test
    public void testHttpsAndNodeSSLFailedCipher() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)

                .put("searchguard.ssl.http.enabled", true).put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
      
                .put("searchguard.ssl.transport.enabled_ciphers","INVALID_CIPHER")
                
                .build();

        try {
            startES(settings);
            Assert.fail();
        } catch (ElasticsearchSecurityException e) {
            if(allowOpenSSL) {
                Assert.assertTrue(e.toString(), e.toString().contains("failed to set cipher suite"));
            } else {
                Assert.assertTrue(e.toString(), e.toString().contains("no valid cipher"));
            }
        }
    }

    @Test
    public void testHttpPlainFail() throws Exception {
        thrown.expect(NoHttpResponseException.class);

        enableHTTPClientSSL = false;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "OPTIONAL")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }

    @Test
    public void testHttpsNoEnforce() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "NONE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE"));
    }
    
    @Test
    public void testHttpsEnforceFail() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        try {
            executeSimpleRequest("");
            Assert.fail();
        } catch (SSLHandshakeException e) {
            //expected
            System.out.println("Expected SSLHandshakeException "+e.toString());
        } catch (SocketException e) {
            //expected
            System.out.println("Expected SocketException "+e.toString());
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("Unexpected exception "+e.toString());
        }
    }

    @Test
    public void testHttpsV3Fail() throws Exception {
        thrown.expect(SSLHandshakeException.class);

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = false;
        enableHTTPClientSSLv3Only = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "NONE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").length() > 0);
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
    }

    // transport
    @Test
    public void testTransportClientSSL() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();

        startES(settings);
        
        log.debug("Elasticsearch started");

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("path.home", ".").put(settings).build();

        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(SearchGuardSSLPlugin.class))) {
            
            log.debug("TransportClient built, connect now to {}:{}", nodeHost, nodePort);
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());            
            log.debug("TransportClient connected");           
            Assert.assertEquals("test", tc.index(new IndexRequest("test","test").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"a\":5}")).actionGet().getIndex());            
            log.debug("Index created");           
            Assert.assertEquals(1L, tc.search(new SearchRequest("test")).actionGet().getHits().getTotalHits());
            log.debug("Search done");
            Assert.assertEquals(3, tc.admin().cluster().health(new ClusterHealthRequest("test")).actionGet().getNumberOfNodes());
            log.debug("ClusterHealth done");            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());           
            log.debug("NodesInfoRequest asserted");
        }
    }

    @Test
    public void testTransportClientSSLExternalContext() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();

        startES(settings);
        
        log.debug("Elasticsearch started");

        final Settings tcSettings = Settings.builder()
                .put("cluster.name", clustername)
                .put("path.home", ".")
                .put("searchguard.ssl.client.external_context_id", "abcx")
                .build();

        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory
                .getDefaultAlgorithm());
        final KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(this.getClass().getResourceAsStream("/truststore.jks"), "changeit".toCharArray());
        tmf.init(trustStore);

        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory
                .getDefaultAlgorithm());
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(this.getClass().getResourceAsStream("/node-0-keystore.jks"), "changeit".toCharArray());        
        kmf.init(keyStore, "changeit".toCharArray());
        
        
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        ExternalSearchGuardKeyStore.registerExternalSslContext("abcx", sslContext);
        
        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(SearchGuardSSLPlugin.class))) {
            
            log.debug("TransportClient built, connect now to {}:{}", nodeHost, nodePort);
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            
            log.debug("TransportClient connected");
            
            Assert.assertEquals("test", tc.index(new IndexRequest("test","test").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"a\":5}")).actionGet().getIndex());
            
            log.debug("Index created");
            
            Assert.assertEquals(1L, tc.search(new SearchRequest("test")).actionGet().getHits().getTotalHits());

            log.debug("Search done");
            
            Assert.assertEquals(3, tc.admin().cluster().health(new ClusterHealthRequest("test")).actionGet().getNumberOfNodes());

            log.debug("ClusterHealth done");
            
            //Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().length);
            
            //log.debug("NodesInfoRequest asserted");
            
        }
    }
    
    @Test
    public void testNodeClientSSL() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .build();

        startES(settings);      

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("node.client", true).put("path.home", ".")
                .put(settings)// -----
                .build();

        try (Node node = new PluginAwareNode(tcSettings, Netty4Plugin.class, SearchGuardSSLPlugin.class).start()) {
            ClusterHealthResponse res = node.client().admin().cluster().health(new ClusterHealthRequest().waitForNodes("4").timeout(TimeValue.timeValueSeconds(5))).actionGet();
            Assert.assertFalse(res.isTimedOut());
            Assert.assertEquals(4, res.getNumberOfNodes());
            Assert.assertEquals(4, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
        }
    }

    @Test
    public void testTransportClientSSLFail() throws Exception {
        thrown.expect(NoNodeAvailableException.class);

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();

        startES(settings);

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put("path.home", getAbsoluteFilePathFromClassPath("node-0-keystore.jks").getParent())
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore_fail.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();

        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(SearchGuardSSLPlugin.class))) {
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
        }
    }

    @Test
    public void testAvailCiphers() throws Exception {
        final SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(null, null, null);
        final SSLEngine engine = serverContext.createSSLEngine();
        final List<String> jdkSupportedCiphers = new ArrayList<>(Arrays.asList(engine.getSupportedCipherSuites()));
        jdkSupportedCiphers.retainAll(SSLConfigConstants.getSecureSSLCiphers(Settings.EMPTY, false));
        engine.setEnabledCipherSuites(jdkSupportedCiphers.toArray(new String[0]));

        final List<String> jdkEnabledCiphers = Arrays.asList(engine.getEnabledCipherSuites());
        // example
        // TLS_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        // TLS_RSA_WITH_AES_128_CBC_SHA, TLS_DHE_RSA_WITH_AES_128_CBC_SHA
        System.out.println("JDK enabled ciphers: " + jdkEnabledCiphers);
        Assert.assertTrue(jdkEnabledCiphers.size() > 0);
    }
    
    @Test
    public void testUnmodifieableCipherProtocolConfig() throws Exception {
        SSLConfigConstants.getSecureSSLProtocols(Settings.EMPTY, false)[0] = "bogus";
        Assert.assertEquals("TLSv1.2", SSLConfigConstants.getSecureSSLProtocols(Settings.EMPTY, false)[0]);
        
        try {
            SSLConfigConstants.getSecureSSLCiphers(Settings.EMPTY, false).set(0, "bogus");
            Assert.fail();
        } catch (UnsupportedOperationException e) {
            //expected
        }
    }
    
    @Test
    public void testCustomPrincipalExtractor() throws Exception {
        
        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;
        
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .put("searchguard.ssl.transport.principal_extractor_class", "com.floragunn.searchguard.ssl.TestPrincipalExtractor")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);
        
        log.debug("Elasticsearch started");

        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("path.home", ".").put(settings).build();

        try (TransportClient tc = new TransportClientImpl(tcSettings, asCollection(SearchGuardSSLPlugin.class))) {
            
            log.debug("TransportClient built, connect now to {}:{}", nodeHost, nodePort);
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());            
            log.debug("TransportClient connected");
            TestPrincipalExtractor.reset();
            Assert.assertEquals("test", tc.index(new IndexRequest("test","test").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"a\":5}")).actionGet().getIndex());            
            log.debug("Index created");           
            Assert.assertEquals(1L, tc.search(new SearchRequest("test")).actionGet().getHits().getTotalHits());
            log.debug("Search done");
            Assert.assertEquals(3, tc.admin().cluster().health(new ClusterHealthRequest("test")).actionGet().getNumberOfNodes());
            log.debug("ClusterHealth done");            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());           
            log.debug("NodesInfoRequest asserted");
        }
        
        executeSimpleRequest("_searchguard/sslinfo?pretty");
        
        //we need to test this in SG itself because in the SSL only plugin the info is not longer propagated
        //Assert.assertTrue(TestPrincipalExtractor.getTransportCount() > 0);
        Assert.assertTrue(TestPrincipalExtractor.getHttpCount() > 0);
    }

    @Test
    public void testCRLPem() throws Exception {
        
        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("root-ca.pem"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)

                .put("searchguard.ssl.http.enabled", true)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMCERT_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.crt.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMKEY_FILEPATH, getAbsoluteFilePathFromClassPath("node-0.key.pem"))
                //.put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMKEY_PASSWORD, "changeit")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_PEMTRUSTEDCAS_FILEPATH, getAbsoluteFilePathFromClassPath("chain-ca.pem"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_CRL_VALIDATE, true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_CRL_VALIDATION_DATE, 1493231675442L)
                .build();

        startES(settings);
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
    }
    
    @Test
    public void testCRL() throws Exception {
        
        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("searchguard.ssl.http.clientauth_mode", "REQUIRE")
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_CRL_VALIDATE, true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_CRL_FILE, getAbsoluteFilePathFromClassPath("crl/revoked.crl"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_CRL_VALIDATION_DATE, 1493231675442L)
                .build();

        startES(settings);

        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        
    }
}
