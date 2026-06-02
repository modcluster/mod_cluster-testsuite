package org.jboss.modcluster.test.auth;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configures Elytron EXTERNAL mechanism authentication on WildFly workers
 * and httpd REMOTE_USER injection on the balancer.
 *
 * <p>This enables end-to-end testing of AJP authentication propagation:
 * httpd authenticates the user via {@code mod_auth_basic} → sets {@code REMOTE_USER}
 * → {@code mod_proxy_ajp} forwards it as the AJP REMOTE_USER attribute → Undertow
 * receives it → Elytron's EXTERNAL mechanism authenticates the user.</p>
 */
public class AjpAuthConfigurator {

    private static final Logger log = LoggerFactory.getLogger(AjpAuthConfigurator.class);

    private static final String REALM_NAME = "ajp-auth-realm";
    private static final String ROLE_DECODER_NAME = "ajp-role-decoder";
    private static final String SECURITY_DOMAIN_NAME = "ajp-auth-sd";
    private static final String AUTH_FACTORY_NAME = "ajp-auth-factory";
    private static final String APP_SECURITY_DOMAIN = "ajp-auth-domain";

    /**
     * Configure Elytron on a worker for EXTERNAL mechanism authentication.
     * Creates a filesystem-realm with the given users, wires up the security domain
     * and http-authentication-factory, and links it to Undertow.
     *
     * @param worker the WildFly worker to configure
     * @param users  user entries (username → role)
     */
    public void configureWorker(WildFlyWorker worker, UserEntry... users) throws Exception {
        Operations ops = worker.getOperations();

        Address realmAddr = Address.subsystem("elytron").and("filesystem-realm", REALM_NAME);
        if (!ops.exists(realmAddr)) {
            ops.add(realmAddr, Values.of("path", "ajp-auth-users")
                    .and("relative-to", "jboss.server.config.dir")).assertSuccess();
        }

        for (UserEntry user : users) {
            ModelNodeResult result = ops.invoke("add-identity", realmAddr,
                    Values.of("identity", user.username));
            if (result.isSuccess()) {
                ops.invoke("add-identity-attribute", realmAddr,
                        Values.of("identity", user.username)
                                .and("name", "Roles")
                                .andList("value", user.role)).assertSuccess();
                log.info("Added user '{}' with role '{}' to realm", user.username, user.role);
            } else {
                log.info("User '{}' already exists in realm, skipping", user.username);
            }
        }

        Address decoderAddr = Address.subsystem("elytron").and("simple-role-decoder", ROLE_DECODER_NAME);
        if (!ops.exists(decoderAddr)) {
            ops.add(decoderAddr, Values.of("attribute", "Roles")).assertSuccess();
        }

        Address domainAddr = Address.subsystem("elytron").and("security-domain", SECURITY_DOMAIN_NAME);
        if (!ops.exists(domainAddr)) {
            ModelNode realmEntry = new ModelNode();
            realmEntry.get("realm").set(REALM_NAME);
            realmEntry.get("role-decoder").set(ROLE_DECODER_NAME);

            ops.add(domainAddr, Values.of("default-realm", REALM_NAME)
                    .and("permission-mapper", "default-permission-mapper")
                    .andList("realms", realmEntry)).assertSuccess();
        }

        Address factoryAddr = Address.subsystem("elytron")
                .and("http-authentication-factory", AUTH_FACTORY_NAME);
        if (!ops.exists(factoryAddr)) {
            ModelNode mechanismEntry = new ModelNode();
            mechanismEntry.get("mechanism-name").set("EXTERNAL");

            ops.add(factoryAddr, Values.of("security-domain", SECURITY_DOMAIN_NAME)
                    .and("http-server-mechanism-factory", "global")
                    .andList("mechanism-configurations", mechanismEntry)).assertSuccess();
        }

        Address appSecDomain = Address.subsystem("undertow")
                .and("application-security-domain", APP_SECURITY_DOMAIN);
        if (!ops.exists(appSecDomain)) {
            ops.add(appSecDomain, Values.of("http-authentication-factory", AUTH_FACTORY_NAME)).assertSuccess();
        }

        worker.reload();
        log.info("Elytron EXTERNAL mechanism configured on worker '{}'", worker.getName());
    }

    /**
     * Configure httpd to proxy requests to the secured app via mod_proxy_ajp
     * with REMOTE_USER set via Basic authentication. This uses a direct
     * {@code ProxyPass} to the worker's AJP port.
     *
     * <p>When {@code username} is non-null, httpd Basic auth is configured with
     * an htpasswd file. After authenticating the user, httpd sets the AJP protocol's
     * {@code remote_user} attribute, which Undertow's AJP listener forwards to
     * Elytron's EXTERNAL mechanism. This is the same AJP attribute path that
     * IIS/isapi_redirect uses after Windows authentication.</p>
     *
     * @param balancer  the httpd balancer
     * @param username  the username to inject as REMOTE_USER
     * @param ajpPort   the worker's AJP listener port
     */
    public void configureBalancerRemoteUser(Balancer balancer, String username, int ajpPort)
            throws Exception {
        StringBuilder conf = new StringBuilder();
        conf.append("<IfModule !authn_file_module>\n");
        conf.append("    LoadModule authn_file_module modules/mod_authn_file.so\n");
        conf.append("</IfModule>\n");
        conf.append("<IfModule !authn_core_module>\n");
        conf.append("    LoadModule authn_core_module modules/mod_authn_core.so\n");
        conf.append("</IfModule>\n");
        conf.append("<IfModule !authz_user_module>\n");
        conf.append("    LoadModule authz_user_module modules/mod_authz_user.so\n");
        conf.append("</IfModule>\n");
        conf.append("<IfModule !auth_basic_module>\n");
        conf.append("    LoadModule auth_basic_module modules/mod_auth_basic.so\n");
        conf.append("</IfModule>\n\n");

        conf.append("ProxyPass /secured/ ajp://localhost:").append(ajpPort).append("/secured/\n");
        conf.append("ProxyPassReverse /secured/ ajp://localhost:").append(ajpPort).append("/secured/\n\n");

        if (username != null) {
            String htpasswdPath = balancer.getServerHome() + "/conf/test-users.htpasswd";
            conf.append("<Location /secured>\n");
            conf.append("    AuthType Basic\n");
            conf.append("    AuthName \"Test\"\n");
            conf.append("    AuthBasicProvider file\n");
            conf.append("    AuthUserFile \"").append(htpasswdPath).append("\"\n");
            conf.append("    Require valid-user\n");
            conf.append("</Location>\n");

            balancer.execCommand("htpasswd", "-cb", htpasswdPath, username, "password");
        }

        Path tempConf = Files.createTempFile("ajp-auth", ".conf");
        Files.writeString(tempConf, conf.toString());
        balancer.copyLocalFile(tempConf, balancer.getConfDir() + "/extra/ajp-auth.conf");

        balancer.reload();
        log.info("Configured direct AJP proxy to port {} with REMOTE_USER='{}'", ajpPort, username);
    }

    /** A username-to-role mapping for the Elytron filesystem realm. */
    public static class UserEntry {
        final String username;
        final String role;

        public UserEntry(String username, String role) {
            this.username = username;
            this.role = role;
        }
    }
}
