package yetti.util;

import clojure.lang.IFn;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

public class HandlerWrapper extends AbstractHandler {
  private final IFn handler;

  public HandlerWrapper(IFn wrappedFn) {
    super();
    this.handler = wrappedFn;
  }

  public void handle(String target,
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
    sch.setAllowNullPathInfo(true);
    JettyWebSocketServletContainerInitializer.configure(sch, null);

    sch.setHandler(new HandlerWrapper(handler));
    return sch;
  }
}
