/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.netty.filter;

import com.google.common.base.Strings;
import com.netflix.config.DynamicStringProperty;
import com.netflix.netty.common.ByteBufUtil;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.impl.Preconditions;
import com.netflix.zuul.FilterLoader;
import com.netflix.zuul.FilterUsageNotifier;
import com.netflix.zuul.context.CommonContextKeys;
import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.filters.Endpoint;
import com.netflix.zuul.filters.FilterType;
import com.netflix.zuul.filters.SyncZuulFilterAdapter;
import com.netflix.zuul.filters.ZuulFilter;
import com.netflix.zuul.filters.endpoint.MissingEndpointHandlingFilter;
import com.netflix.zuul.filters.endpoint.ProxyEndpoint;
import com.netflix.zuul.message.ZuulMessage;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.message.http.HttpResponseMessageImpl;
import com.netflix.zuul.netty.server.MethodBinding;
import io.netty.handler.codec.http.HttpContent;
import io.netty.util.ReferenceCountUtil;
import io.perfmark.PerfMark;
import io.perfmark.TaskCloseable;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is supposed to be thread safe and hence should not have any non final member variables
 * Created by saroskar on 5/18/17.
 */
@ThreadSafe
public class ZuulEndPointRunner extends BaseZuulFilterRunner<HttpRequestMessage, HttpResponseMessage> {

    private final FilterLoader filterLoader;

    private static final Logger logger = LoggerFactory.getLogger(ZuulEndPointRunner.class);
    public static final String PROXY_ENDPOINT_FILTER_NAME = ProxyEndpoint.class.getCanonicalName();
    public static final DynamicStringProperty DEFAULT_ERROR_ENDPOINT =
            new DynamicStringProperty("zuul.filters.error.default", "endpoint.ErrorResponse");

    public ZuulEndPointRunner(
            FilterUsageNotifier usageNotifier,
            FilterLoader filterLoader,
            FilterRunner<HttpResponseMessage, HttpResponseMessage> respFilters,
            Registry registry) {
        super(FilterType.ENDPOINT, usageNotifier, respFilters, registry);
        this.filterLoader = filterLoader;
    }

    @Nullable public static ZuulFilter<HttpRequestMessage, HttpResponseMessage> getEndpoint(
            @Nullable HttpRequestMessage zuulReq) {
        if (zuulReq != null) {
            return zuulReq.getContext().get(CommonContextKeys.ZUUL_ENDPOINT);
        }
        return null;
    }

    protected ZuulFilter<HttpRequestMessage, HttpResponseMessage> getEndpoint(
            String endpointName, HttpRequestMessage zuulRequest) {
        SessionContext zuulCtx = zuulRequest.getContext();

        if (zuulCtx.getStaticResponse() != null) {
            return STATIC_RESPONSE_ENDPOINT;
        }

        if (endpointName == null) {
            return new MissingEndpointHandlingFilter("NO_ENDPOINT_NAME");
        }

        if (endpointName.equals(PROXY_ENDPOINT_FILTER_NAME)) {
            return newProxyEndpoint(zuulRequest);
        }

        Endpoint<HttpRequestMessage, HttpResponseMessage> filter = getEndpointFilter(endpointName);
        if (filter == null) {
            return new MissingEndpointHandlingFilter(endpointName);
        }

        return filter;
    }

    public static void setEndpoint(
            HttpRequestMessage zuulReq, ZuulFilter<HttpRequestMessage, HttpResponseMessage> endpoint) {
        zuulReq.getContext().put(CommonContextKeys.ZUUL_ENDPOINT, endpoint);
    }

    @Override
    public void filter(HttpRequestMessage zuulReq) {
        if (zuulReq.getContext().isCancelled()) {
            PerfMark.event(getClass().getName(), "filterCancelled");
            zuulReq.disposeBufferedBody();
            logger.debug("Request was cancelled, UUID {}", zuulReq.getContext().getUUID());
            return;
        }

        String endpointName = getEndPointName(zuulReq.getContext());
        try (TaskCloseable ignored = PerfMark.traceTask(this, s -> s.getClass().getSimpleName() + ".filter")) {
            Preconditions.checkNotNull(zuulReq, "input message");
            addPerfMarkTags(zuulReq);

            ZuulFilter<HttpRequestMessage, HttpResponseMessage> endpoint = getEndpoint(endpointName, zuulReq);
            logger.debug(
                    "Got endpoint {}, UUID {}",
                    endpoint.filterName(),
                    zuulReq.getContext().getUUID());
            setEndpoint(zuulReq, endpoint);
            HttpResponseMessage zuulResp = filter(endpoint, zuulReq);

            if ((zuulResp != null) && !(endpoint instanceof ProxyEndpoint)) {
                // EdgeProxyEndpoint calls invokeNextStage internally
                logger.debug(
                        "Endpoint calling invokeNextStage, UUID {}",
                        zuulReq.getContext().getUUID());
                invokeNextStage(zuulResp);
            }
        } catch (Exception ex) {
            handleException(zuulReq, endpointName, ex);
        }
    }

    @Override
    public void filter(HttpRequestMessage zuulReq, HttpContent chunk) {
        if (zuulReq.getContext().isCancelled()) {
            chunk.release();
            return;
        }

        String endpointName = "-";
        try (TaskCloseable ignored = PerfMark.traceTask(this, s -> s.getClass().getSimpleName() + ".filterChunk")) {
            addPerfMarkTags(zuulReq);
            ZuulFilter<HttpRequestMessage, HttpResponseMessage> endpoint =
                    Preconditions.checkNotNull(getEndpoint(zuulReq), "endpoint");
            endpointName = endpoint.filterName();

            ByteBufUtil.touch(chunk, "Endpoint processing chunk, ZuulMessage: ", zuulReq);
            HttpContent newChunk = endpoint.processContentChunk(zuulReq, chunk);
            if (newChunk != null) {
                ByteBufUtil.touch(newChunk, "Endpoint buffering newChunk, ZuulMessage: ", zuulReq);
                // Endpoints do not directly forward content chunks to next stage in the filter chain.
                zuulReq.bufferBodyContents(newChunk);

                // deallocate original chunk if necessary
                if (newChunk != chunk) {
                    chunk.release();
                }

                if (isFilterAwaitingBody(zuulReq.getContext())
                        && zuulReq.hasCompleteBody()
                        && !(endpoint instanceof ProxyEndpoint)) {
                    // whole body has arrived, resume filter chain
                    ByteBufUtil.touch(newChunk, "Endpoint body complete, resume chain, ZuulMessage: ", zuulReq);
                    invokeNextStage(filter(endpoint, zuulReq));
                }
            }
        } catch (Exception ex) {
            ReferenceCountUtil.safeRelease(chunk);
            handleException(zuulReq, endpointName, ex);
        }
    }

    @Override
    protected void resume(HttpResponseMessage zuulMesg) {
        try (TaskCloseable ignored = PerfMark.traceTask(this, s -> s.getClass().getSimpleName() + ".resume")) {
            if (zuulMesg.getContext().isCancelled()) {
                return;
            }
            invokeNextStage(zuulMesg);
        }
    }

    protected String getEndPointName(SessionContext zuulCtx) {
        if (zuulCtx.shouldSendErrorResponse()) {
            zuulCtx.setShouldSendErrorResponse(false);
            zuulCtx.setErrorResponseSent(true);
            String errEndPointName = zuulCtx.getErrorEndpoint();
            return Strings.isNullOrEmpty(errEndPointName) ? DEFAULT_ERROR_ENDPOINT.get() : errEndPointName;
        } else {
            return zuulCtx.getEndpoint();
        }
    }

    /**
     * Override to inject your own proxy endpoint implementation
     *
     * @param zuulRequest - the request message
     * @return the proxy endpoint
     */
    protected ZuulFilter<HttpRequestMessage, HttpResponseMessage> newProxyEndpoint(HttpRequestMessage zuulRequest) {
        return new ProxyEndpoint(
                zuulRequest, getChannelHandlerContext(zuulRequest), getNextStage(), MethodBinding.NO_OP_BINDING);
    }

    protected <I extends ZuulMessage, O extends ZuulMessage> Endpoint<I, O> getEndpointFilter(String endpointName) {
        return (Endpoint<I, O>) filterLoader.getFilterByNameAndType(endpointName, FilterType.ENDPOINT);
    }

    protected static final ZuulFilter<HttpRequestMessage, HttpResponseMessage> STATIC_RESPONSE_ENDPOINT =
            new SyncZuulFilterAdapter<HttpRequestMessage, HttpResponseMessage>() {
                @Override
                public HttpResponseMessage apply(HttpRequestMessage request) {
                    HttpResponseMessage resp = request.getContext().getStaticResponse();
                    resp.finishBufferedBodyIfIncomplete();
                    return resp;
                }

                @Override
                public String filterName() {
                    return "StaticResponseEndpoint";
                }

                @Override
                public HttpResponseMessage getDefaultOutput(HttpRequestMessage input) {
                    return HttpResponseMessageImpl.defaultErrorResponse(input);
                }
            };
}
