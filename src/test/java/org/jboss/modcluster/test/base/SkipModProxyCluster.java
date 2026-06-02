package org.jboss.modcluster.test.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that requires httpd without mod_proxy_cluster.
 * The {@link ModClusterTestExtension} reads this annotation and configures
 * {@code NativeHttpdBalancer} to skip loading mod_proxy_cluster modules,
 * allowing direct {@code ProxyPass} / {@code mod_proxy_ajp} usage.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipModProxyCluster {
}
