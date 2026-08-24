package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.commands.socketbindings.AddSocketBinding;
import org.wildfly.extras.creaper.commands.socketbindings.RemoveSocketBinding;
import org.wildfly.extras.creaper.commands.undertow.AddUndertowListener;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Manages Undertow subsystem configuration for WildFly containers.
 * Handles server creation, socket bindings, and listener management.
 */
public class WildFlyUndertowManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyUndertowManager.class);

    private final WildFlyWorker container;

    WildFlyUndertowManager(WildFlyWorker container) {
        this.container = container;
    }

    /**
     * Create a new Undertow server.
     *
     * @param serverName name of the server to create
     * @throws Exception if the management operation fails
     */
    public void addServer(final String serverName) throws Exception {
        Operations ops = container.getOperations();
        Address serverAddress = Address.subsystem("undertow").and("server", serverName);

        ops.add(serverAddress).assertSuccess("Adding Undertow server '" + serverName + "' failed");
        log.info("Added Undertow server '{}' on worker '{}'", serverName, container.getName());
    }

    /**
     * Remove an Undertow server if it exists.
     *
     * @param serverName name of the server to remove
     * @throws Exception if the management operation fails
     */
    public void removeServer(final String serverName) throws Exception {
        Operations ops = container.getOperations();
        Address serverAddress = Address.subsystem("undertow").and("server", serverName);

        ops.removeIfExists(serverAddress);
        log.info("Removed Undertow server '{}' from worker '{}'", serverName, container.getName());
    }

    /**
     * Create a socket binding with the given name and port.
     *
     * @param name name of the socket binding
     * @param port port number
     * @throws Exception if the management operation fails
     */
    public void addSocketBinding(final String name, final int port) throws Exception {
        OnlineManagementClient client = container.getManagementClient();

        client.apply(new AddSocketBinding.Builder(name)
                .port(port)
                .build());
        log.info("Added socket binding '{}' with port {} on worker '{}'", name, port, container.getName());
    }

    /**
     * Remove a socket binding by name.
     *
     * @param name name of the socket binding to remove
     * @throws Exception if the management operation fails
     */
    public void removeSocketBinding(final String name) throws Exception {
        OnlineManagementClient client = container.getManagementClient();

        client.apply(new RemoveSocketBinding(name));
        log.info("Removed socket binding '{}' from worker '{}'", name, container.getName());
    }

    /**
     * Add an HTTP listener to a given Undertow server.
     *
     * @param listenerName name of the HTTP listener
     * @param serverName name of the Undertow server to add the listener to
     * @param socketBindingName name of the socket binding to use
     * @throws Exception if the management operation fails
     */
    public void addHttpListener(final String listenerName, final String serverName,
                                final String socketBindingName) throws Exception {
        OnlineManagementClient client = container.getManagementClient();

        client.apply(new AddUndertowListener.HttpBuilder(listenerName, serverName, socketBindingName)
                .build());
        log.info("Added HTTP listener '{}' on server '{}' with socket binding '{}' on worker '{}'",
                listenerName, serverName, socketBindingName, container.getName());
    }

    /**
     * Set the enable-http2 attribute on an HTTP listener.
     * When disabled, the listener will not accept HTTP/2 connections (h2c upgrade).
     * This is required for WebSocket support through the mod_cluster proxy,
     * as HTTP/2 connections do not support HTTP/1.1 Upgrade.
     * Requires a server reload to take effect.
     *
     * @param serverName name of the Undertow server (e.g., "default-server")
     * @param listenerName name of the HTTP listener (e.g., "default")
     * @param enable whether to enable HTTP/2
     * @throws Exception if the management operation fails
     */
    public void setHttpListenerEnableHttp2(final String serverName, final String listenerName,
                                           final boolean enable) throws Exception {
        Operations ops = container.getOperations();
        Address listenerAddr = Address.subsystem("undertow")
                .and("server", serverName)
                .and("http-listener", listenerName);

        ops.writeAttribute(listenerAddr, "enable-http2", enable).assertSuccess();
        log.info("Set enable-http2={} on http-listener '{}' (server '{}') on worker '{}'",
                enable, listenerName, serverName, container.getName());
    }

    /**
     * Add an AJP listener to a given Undertow server.
     *
     * <p>Creates a socket binding on the given port (if it does not already exist),
     * then adds an AJP listener bound to it on the specified server (if it does not
     * already exist).</p>
     *
     * <p>Idempotent — safe to call multiple times; existing resources are skipped.
     * Does not reload the server; call {@link WildFlyWorker#reload()} after all
     * listeners have been added.</p>
     *
     * @param listenerName name of the AJP listener (e.g., {@code "ajp"})
     * @param serverName the Undertow server to add the listener to (e.g., {@code "default-server"})
     * @param socketBindingName name of the socket binding (e.g., {@code "ajp"})
     * @param port the port for the socket binding (e.g., 8009)
     * @throws Exception if the management operation fails
     * @see WildFlyModClusterManager#setListener(String)
     */
    public void addAjpListener(final String listenerName, final String serverName,
                               final String socketBindingName, final int port) throws Exception {
        Operations ops = container.getOperations();

        // UNDERTOW-2791 enforces REQUIRE_AJP_SECRET=true by default. The secret
        // check in AjpReadListener runs before the packet-type dispatch, so CPING
        // health checks (which carry no secret) are rejected — breaking
        // mod_proxy_cluster's connection pool. Disable until fixed.
        Address requireSecretProp = Address.of("system-property", "io.undertow.ajp.REQUIRE_AJP_SECRET");
        if (!ops.exists(requireSecretProp)) {
            ops.add(requireSecretProp, Values.of("value", "false")).assertSuccess();
            container.reload();
        }

        Address sbAddr = Address.of("socket-binding-group", "standard-sockets")
                .and("socket-binding", socketBindingName);
        if (!ops.exists(sbAddr)) {
            addSocketBinding(socketBindingName, port);
        }

        Address listenerAddr = Address.subsystem("undertow")
                .and("server", serverName)
                .and("ajp-listener", listenerName);
        if (!ops.exists(listenerAddr)) {
            OnlineManagementClient client = container.getManagementClient();
            client.apply(new AddUndertowListener.AjpBuilder(listenerName, serverName, socketBindingName)
                    .build());
        }

        log.info("AJP listener '{}' on port {} on server '{}' added on worker '{}'",
                listenerName, port, serverName, container.getName());
    }
}
