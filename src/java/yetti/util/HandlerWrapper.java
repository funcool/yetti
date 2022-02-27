package yetti.util;

import clojure.lang.IFn;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

public class HandlerWrapper extends ServletHandler {
  private final IFn handler;

  public HandlerWrapper(IFn wrappedFn) {
    super();
    this.handler = wrappedFn;
  }

  public void doHandle(String target,
                       Request baseRequest,
                       HttpServletRequest request,
                       HttpServletResponse response) throws java.io.IOException {
    try {
      this.handler.invoke(request, response);
    } catch (Throwable e) {
      response.sendError(500, e.getMessage());
    } finally {
      baseRequest.setHandled(true);
    }
  }

  public static ServletContextHandler wrap(IFn handler) {
    final var sch = new ServletContextHandler();
    // sch.setContextPath("/");
    sch.setAllowNullPathInfo(true);
    JettyWebSocketServletContainerInitializer.configure(sch, null);

    sch.setServletHandler(new HandlerWrapper(handler));
    return sch;
  }
}
