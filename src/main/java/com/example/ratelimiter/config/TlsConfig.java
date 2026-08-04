package com.example.ratelimiter.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures embedded Tomcat to enforce TLS 1.2 and TLS 1.3 only.
 * Relies on SSL keystore configuration present in application-dev/prod.yml.
 * Only active when {@code server.ssl.enabled=true} so the plain-HTTP profile is unaffected.
 *
 * <p>The SSL host config is created by Spring Boot's {@code SslConnectorCustomizer};
 * this customizer patches its enabled protocols. If no config exists yet (or SSL is
 * disabled), Tomcat's defaults already restrict JSSE to TLSv1.2/TLSv1.3 on JDK 21.
 */
@Configuration
@ConditionalOnProperty(name = "server.ssl.enabled", havingValue = "true")
public class TlsConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers((Connector connector) -> {
            connector.setSecure(true);
            connector.setScheme("https");
            if (connector.getProtocolHandler() instanceof AbstractHttp11JsseProtocol<?> protocol) {
                SSLHostConfig[] hostConfigs = protocol.findSslHostConfigs();
                if (hostConfigs.length > 0) {
                    hostConfigs[0].setProtocols("TLSv1.2,TLSv1.3");
                }
            }
        });
    }
}
