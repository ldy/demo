package com.example.gateway.filter;

import cn.hutool.json.JSONUtil;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.bouncycastle.util.Strings;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class RequestBodyGatewayFilter implements Ordered, GlobalFilter {

    private static final String COUNT_START_TIME = "countStartTime";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.traceId();

        log.info("RequestBodyGatewayFilter.............................>");
        exchange.getAttributes().put(COUNT_START_TIME, System.currentTimeMillis());

        if (exchange.getRequest().getHeaders().getContentType() == null) {
            log.info("url : {} 开始, traceId is {}", exchange.getRequest().getURI().getRawPath(),
                traceId);
        } else if (exchange.getRequest().getHeaders().getContentLength() > 0) {

            return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    ServerHttpRequest request = exchange.getRequest();
                    DataBufferUtils.retain(dataBuffer);
                    Flux<DataBuffer> cachedFlux = Flux
                        .defer(
                            () -> Flux.just(dataBuffer.slice(0, dataBuffer.readableByteCount())));
                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(
                        exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return cachedFlux;
                        }

                    };
                    String raw = resolveBodyFromRequest(cachedFlux);
                    String jsonObject = JSONUtil.toJsonStr(raw);
                    log.info("url : {} 开始 ,request body is:{}, traceId is {}",
                        exchange.getRequest().getURI().getRawPath(), jsonObject, traceId);

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
        }
        return chain.filter(exchange);
    }

    /**
     * 用于获取请求参数
     */
    public static String resolveBodyFromRequest(Flux<DataBuffer> body) {
        AtomicReference<String> rawRef = new AtomicReference<>();
        body.subscribe(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            rawRef.set(Strings.fromUTF8ByteArray(bytes));
        });
        return rawRef.get();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}