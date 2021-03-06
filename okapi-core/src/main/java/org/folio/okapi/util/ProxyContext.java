package org.folio.okapi.util;

import com.codahale.metrics.Timer;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.bean.ModuleInstance;
import org.folio.okapi.common.ErrorType;
import org.folio.okapi.common.HttpResponse;
import org.folio.okapi.common.OkapiClient;
import org.folio.okapi.common.OkapiLogger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.okapi.common.Messages;

/**
 * Helper for carrying around those things we need for proxying. Can also be
 * used for Okapi's own services, without the modList. Also has lots of helpers
 * for logging, in order to get the request-id in most log messages.
 */
@java.lang.SuppressWarnings({"squid:S1192"})
public class ProxyContext {

  private final Logger logger = OkapiLogger.get();
  private List<ModuleInstance> modList;
  private final String reqId;
  private String tenant;
  private final RoutingContext ctx;
  private Timer.Context timer;
  private Long timerId;
  private final int waitMs;

  // store auth filter response status code, headers, and body
  private int authRes;
  private MultiMap authHeaders = MultiMap.caseInsensitiveMultiMap();
  private Buffer authResBody = Buffer.buffer();
  // store handler response status code and headers
  private int handlerRes;
  private MultiMap handlerHeaders = MultiMap.caseInsensitiveMultiMap();

  private Messages messages = Messages.getInstance();

  /**
   * Constructor to be used from proxy. Does not log the request, as we do not
   * know the tenant yet.
   *
   * @param ctx - the request we are serving
   */
  public ProxyContext(RoutingContext ctx, int waitMs) {
    this.ctx = ctx;
    this.waitMs = waitMs;
    this.tenant = "-";
    this.modList = null;
    String curid = ctx.request().getHeader(XOkapiHeaders.REQUEST_ID);
    String path = ctx.request().path();
    if (path == null) { // defensive coding, should always be there
      path = "";
    }
    path = path.replaceFirst("^(/_)?(/[^/?]+).*$", "$2");
      // when rerouting, the query appears as part of the getPath, so we kill it
    // here with the '?'.
    Random r = new Random();
    StringBuilder newid = new StringBuilder();
    newid.append(String.format("%06d", r.nextInt(1000000)));
    newid.append(path);
    if (curid == null || curid.isEmpty()) {
      reqId = newid.toString();
      ctx.request().headers().add(XOkapiHeaders.REQUEST_ID, reqId);
      this.debug("Assigned new reqId " + newid);
    } else {
      reqId = curid + ";" + newid.toString();
      ctx.request().headers().set(XOkapiHeaders.REQUEST_ID, reqId);
      this.debug("Appended a reqId " + newid);
    }
    timer = null;
    timerId = null;
    handlerRes = 0;
  }

  public final void startTimer(String key) {
    closeTimer();
    timer = DropwizardHelper.getTimerContext(key);
    if (waitMs > 0) {
      timerId = ctx.vertx().setPeriodic(waitMs, res
        -> logger.warn(reqId + " WAIT "
          + ctx.request().remoteAddress()
          + " " + tenant + " " + ctx.request().method()
          + " " + ctx.request().path())
      );
    }
  }

  public void closeTimer() {
    if (timerId != null) {
      ctx.vertx().cancelTimer(timerId);
      timerId = null;
    }
    if (timer != null) {
      timer.close();
      timer = null;
    }
  }

  /**
   * Return the elapsed time since startTimer, in microseconds.
   *
   * @return
   */
  public String timeDiff() {
    if (timer != null) {
      return (timer.stop() / 1000) + "us";
    } else {
      return "-";
    }
  }

  /**
   * Pass the response headers from an OkapiClient into the response of this
   * request. Only selected X-Something headers: X-Okapi-Trace, and a special
   * X-Tenant-Perms-Result, which is used in unit tests for the tenantPemissions
   *
   * @param ok OkapiClient to take resp headers from
   */
  public void passOkapiTraceHeaders(OkapiClient ok) {
    MultiMap respH = ok.getRespHeaders();
    for (Map.Entry<String, String> e : respH.entries()) {
      if (XOkapiHeaders.TRACE.equals(e.getKey())
        || "X-Tenant-Perms-Result".equals(e.getKey())) {
        ctx.response().headers().add(e.getKey(), e.getValue());
      }
    }
  }

  public List<ModuleInstance> getModList() {
    return modList;
  }

  public void setModList(List<ModuleInstance> modList) {
    this.modList = modList;
  }

  public String getTenant() {
    return tenant;
  }

  public void setTenant(String tenant) {
    this.tenant = tenant;
  }

  public RoutingContext getCtx() {
    return ctx;
  }

  private String getReqId() {
    return reqId;
  }

  public int getAuthRes() {
    return authRes;
  }

  public void setAuthRes(int authRes) {
    this.authRes = authRes;
  }

  public MultiMap getAuthHeaders() {
    return authHeaders;
  }

  public Buffer getAuthResBody() {
    return authResBody;
  }

  public void setAuthResBody(Buffer authResBody) {
    this.authResBody = authResBody;
  }

  public int getHandlerRes() {
    return handlerRes;
  }

  public void setHandlerRes(int handlerRes) {
    this.handlerRes = handlerRes;
  }

  public MultiMap getHandlerHeaders() {
    return handlerHeaders;
  }

  /* Helpers for logging and building responses */
  public final void logRequest(RoutingContext ctx, String tenant) {
    StringBuilder mods = new StringBuilder();
    if (modList != null && !modList.isEmpty()) {
      for (ModuleInstance mi : modList) {
        mods.append(" ").append(mi.getModuleDescriptor().getId());
      }
    }
    if (logger.isInfoEnabled()) {
      logger.info("{} REQ {} {} {} {} {}", reqId,
        ctx.request().remoteAddress(), tenant, ctx.request().method(),
        ctx.request().path(), mods);
    }
  }

  public void logResponse(String module, String url, int statusCode) {
    if (logger.isInfoEnabled()) {
      logger.info("{} RES {} {} {} {}", reqId,
        statusCode, timeDiff(), module, url);
    }
  }

  public void responseError(ErrorType t, Throwable cause) {
    responseError(ErrorType.httpCode(t), cause);
  }

  private void responseError(int code, Throwable cause) {
    if (cause != null && cause.getMessage() != null) {
      responseError(code, cause.getMessage());
    } else {
      responseError(code, messages.getMessage("10300"));
    }
  }

  public void responseError(int code, String msg) {
    logResponse("okapi", msg, code);
    closeTimer();
    HttpResponse.responseError(ctx, code, msg);
  }

  public void addTraceHeaderLine(String h) {
    ctx.response().headers().add(XOkapiHeaders.TRACE, h);
  }

  public void error(String msg) {
    logger.error("{} {}", getReqId(), msg);
  }

  public void warn(String msg) {
    logger.warn("{} {}", getReqId(), msg);
  }

  public void warn(String msg, Throwable e) {
    logger.warn("{} {}", getReqId(), msg, e);
  }

  public void debug(String msg) {
    logger.debug("{} {}", getReqId(), msg);
  }

  public void trace(String msg) {
    logger.trace("{} {}", getReqId(), msg);
  }

}
