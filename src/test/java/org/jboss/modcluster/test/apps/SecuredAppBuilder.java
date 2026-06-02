package org.jboss.modcluster.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.net.URL;

/**
 * Builder for creating the secured WAR application at runtime using ShrinkWrap.
 * Packages {@link SecuredServlet} with a {@code web.xml} that declares the EXTERNAL
 * auth method and a {@code jboss-web.xml} that maps to the {@code ajp-auth-domain}
 * application security domain.
 */
public class SecuredAppBuilder {

    /**
     * Creates the secured.war file.
     *
     * @return File reference to generated WAR in temp directory
     */
    public static File createSecuredApp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL webXml = cl.getResource("apps/secured/web.xml");
        URL jbossWebXml = cl.getResource("apps/secured/jboss-web.xml");

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "secured.war")
                .addClass(SecuredServlet.class)
                .setWebXML(webXml)
                .addAsWebInfResource(jbossWebXml, "jboss-web.xml");

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "secured.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);
        tempWar.deleteOnExit();

        return tempWar;
    }
}
