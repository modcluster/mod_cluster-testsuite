package org.jboss.modcluster.test.apps;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Servlet secured by the EXTERNAL authentication mechanism.
 * Returns the authenticated user's name and the worker node name.
 * Security constraints are declared in {@code web.xml} with
 * {@code <auth-method>EXTERNAL</auth-method>}.
 */
@WebServlet("/secured")
public class SecuredServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/plain");
        PrintWriter out = resp.getWriter();
        out.println("user=" + req.getRemoteUser());
        out.println("worker=" + System.getProperty("jboss.node.name", "unknown"));
    }
}
