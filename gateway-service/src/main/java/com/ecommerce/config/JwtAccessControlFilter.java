package com.ecommerce.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtAccessControlFilter implements GlobalFilter, Ordered {
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();

        if (isPublic(path, method)) {
            Claims publicClaims = parseClaims(exchange);
            if (publicClaims != null) {
                ServerWebExchange identified = exchange.mutate()
                        .request(exchange.getRequest().mutate()
                                .header("X-Authenticated-User", publicClaims.getSubject())
                                .header("X-Authenticated-Role", publicClaims.get("role", String.class))
                                .build())
                        .build();
                return chain.filter(identified);
            }
            return chain.filter(exchange);
        }

        Claims claims = parseClaims(exchange);
        if (claims == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String username = claims.getSubject();
        String role = claims.get("role", String.class);

        if (requiresAdmin(path, method) && !isAdmin(role)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        if (path.startsWith("/api/orders/user/") && !isAdmin(role)) {
            String requestedUser = path.substring("/api/orders/user/".length());
            if (!requestedUser.equals(username)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }

        if (path.startsWith("/api/order-events/") && !isAdmin(role)) {
            String requestedUser = exchange.getRequest().getQueryParams().getFirst("userId");
            if (requestedUser == null || !requestedUser.equals(username)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }

        if (path.startsWith("/api/order-events/admin/") && !isAdmin(role)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        if (path.startsWith("/api/payments/user/") && !isAdmin(role)) {
            String requestedUser = path.substring("/api/payments/user/".length());
            if (!requestedUser.equals(username)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }

        ServerWebExchange mutated = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .header("X-Authenticated-User", username)
                        .header("X-Authenticated-Role", role)
                        .build())
                .build();
        return chain.filter(mutated);
    }

    private boolean isPublic(String path, HttpMethod method) {
        return path.startsWith("/api/auth/signin")
                || path.startsWith("/api/auth/signup")
                || path.startsWith("/api/assistant")
                || (path.startsWith("/api/products") && !path.startsWith("/api/products/wishlist") && HttpMethod.GET.equals(method));
    }

    private boolean requiresAdmin(String path, HttpMethod method) {
        if (path.equals("/api/auth/users")) {
            return true;
        }
        if (path.startsWith("/api/products") && !HttpMethod.GET.equals(method)) {
            // Wishlist is available to all authenticated users
            if (path.startsWith("/api/products/wishlist")) {
                return false;
            }
            return true;
        }
        if (path.equals("/api/orders") && HttpMethod.GET.equals(method)) {
            return true;
        }
        if (path.equals("/api/orders") && HttpMethod.POST.equals(method)) {
            return true;
        }
        return path.startsWith("/api/orders/") && path.endsWith("/status");
    }

    private boolean isAdmin(String role) {
        return "ROLE_ADMIN".equals(role) || "ROLE_SUPER_ADMIN".equals(role);
    }

    private Claims parseClaims(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (exchange.getRequest().getPath().value().startsWith("/api/order-events/")) {
            token = exchange.getRequest().getQueryParams().getFirst("token");
        }

        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
