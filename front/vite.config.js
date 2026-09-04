import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig(function (_a) {
    var mode = _a.mode;
    var env = loadEnv(mode, '.', '');
    var apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8382';
    return {
        plugins: [react()],
        build: {
            rollupOptions: {
                output: {
                    manualChunks: {
                        'vendor-react': ['react', 'react-dom', 'react-router-dom'],
                    },
                },
            },
        },
        server: {
            port: 8282,
            proxy: {
                '/api': {
                    target: apiProxyTarget,
                    changeOrigin: true,
                },
                '/v1': {
                    target: apiProxyTarget,
                    changeOrigin: true,
                },
            },
        },
    };
});
