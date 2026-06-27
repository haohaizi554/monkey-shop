# MonkeyShop Nginx Edge Baseline

This Nginx config is the WS1 reverse-proxy baseline for environments that do not use a managed ALB or ingress controller.

- TLS is terminated at Nginx with TLS 1.3 only.
- HTTP is redirected to HTTPS.
- HSTS preload and baseline browser security headers are set at the edge.
- Spring Boot remains on the internal `monkey-app:8888` upstream.
- `X-Forwarded-*` headers are passed so Spring can reconstruct the original secure request.
- The canonical public hostname is pinned in redirects and forwarded host headers. Replace `monkeyshop.example.com` before deployment.
- Upload body size is capped at 6 MB to match the application request limit.

Mount a real certificate and key at:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

For Kubernetes, translate the same policy to Ingress annotations and cert-manager TLS resources instead of running this file directly.
