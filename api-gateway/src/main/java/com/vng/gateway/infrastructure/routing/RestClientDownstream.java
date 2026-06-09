package com.vng.gateway.infrastructure.routing;

import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.DownstreamException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RestClientDownstream implements DownstreamClient {

    private final RestClient restClient;

    public RestClientDownstream(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DownstreamResponse forward(DownstreamRequest req) {
        try {
            return restClient.method(HttpMethod.valueOf(req.method()))
                    .uri(req.baseUrl() + req.path())
                    .headers(h -> req.headers().forEach(h::set))
                    .body(req.body() == null ? new byte[0] : req.body())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 500) {
                            throw new DownstreamException(DownstreamException.Type.UPSTREAM_5XX,
                                    "Downstream returned " + status);
                        }
                        byte[] body = response.getBody().readAllBytes();
                        Map<String, String> headers = response.getHeaders().toSingleValueMap();
                        return new DownstreamResponse(status, body, headers);
                    });
        } catch (ResourceAccessException e) {
            // timeout / không kết nối được
            throw new DownstreamException(DownstreamException.Type.TIMEOUT,
                    "Downstream unreachable: " + e.getMessage());
        }
    }
}
