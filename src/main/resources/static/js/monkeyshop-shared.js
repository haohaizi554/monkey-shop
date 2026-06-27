(function () {
    const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
    const nativeFetch = window.fetch.bind(window);

    function readCookie(name) {
        return document.cookie.split('; ').find(row => row.startsWith(name + '='))?.split('=')[1];
    }

    window.fetch = function (input, init = {}) {
        const request = new Request(input, init);
        const url = new URL(request.url, window.location.origin);
        const method = (init.method || request.method || 'GET').toUpperCase();
        if (url.origin === window.location.origin && unsafeMethods.has(method)) {
            const token = readCookie('XSRF-TOKEN');
            if (token) {
                const headers = new Headers(init.headers || request.headers);
                headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
                init = { ...init, headers };
            }
        }
        return nativeFetch(input, init);
    };

    if (window.Vue && window.Vue.createApp) {
        const nativeCreateApp = window.Vue.createApp;
        window.Vue.createApp = function (...args) {
            const app = nativeCreateApp.apply(this, args);
            app.directive('fallback-img', {
                mounted(el, binding) {
                    el.addEventListener('error', () => {
                        const fallback = binding.value || '/images/default_product.png';
                        if (el.src !== fallback) {
                            el.src = fallback;
                        }
                    });
                }
            });
            return app;
        };
    }
})();
