const { createApp } = Vue;
    createApp({
        data() {
            return {
                loginForm: { username: '', password: '' },
                regForm: { username: '', password: '', phone: '', captcha: '' },
                regFile: null,
                previewAvatar: '',
                captchaUrl: '/api/auth/captcha?t=' + Date.now(),

                // 修复：resetForm 增加 captcha 字段
                resetForm: { username: '', phone: '', newPassword: '', captcha: '' },

                loginModal: null, registerModal: null, resetModal: null,
                toasts: []
            }
        },
        mounted() {
            this.loginModal = new bootstrap.Modal(document.getElementById('loginModal'));
            this.registerModal = new bootstrap.Modal(document.getElementById('registerModal'));
            this.resetModal = new bootstrap.Modal(document.getElementById('resetModal'));
        },
        methods: {
            showToast(msg, type = 'success') {
                const id = Date.now();
                this.toasts.push({ id, msg, type, title: type === 'error' ? '操作失败' : '操作成功' });
                setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 3000);
            },

            switchToRegister() {
                this.loginModal.hide(); this.resetModal.hide(); this.registerModal.show();
                this.refreshCaptcha(); this.regFile = null; this.previewAvatar = '';
            },
            switchToLogin() {
                this.registerModal.hide(); this.resetModal.hide(); this.loginModal.show();
            },
            switchToReset() {
                this.loginModal.hide(); this.resetModal.show();
                this.refreshCaptcha(); // 打开重置密码也刷新验证码
            },
            refreshCaptcha() { this.captchaUrl = '/api/auth/captcha?t=' + Date.now(); },

            handleFileChange(event) {
                const file = event.target.files[0];
                if (file) {
                    this.regFile = file;
                    this.previewAvatar = URL.createObjectURL(file);
                }
            },

            async handleRegister() {
                if(!this.regForm.username || !this.regForm.password || !this.regForm.phone || !this.regForm.captcha) {
                    this.showToast("请填写完整信息", 'error'); return;
                }
                const formData = new FormData();
                formData.append('username', this.regForm.username);
                formData.append('password', this.regForm.password);
                formData.append('phone', this.regForm.phone);
                formData.append('captcha', this.regForm.captcha);
                if (this.regFile) formData.append('avatarFile', this.regFile);

                try {
                    const res = await fetch('/api/auth/register', { method: 'POST', body: formData });
                    const result = await res.text();
                    if (result === 'ok') {
                        this.showToast("注册成功！正在跳转...", 'success');
                        setTimeout(() => { this.loginForm.username = this.regForm.username; this.switchToLogin(); }, 1500);
                    } else {
                        this.showToast(result, 'error');
                        this.refreshCaptcha();
                    }
                } catch (e) { this.showToast("网络错误", 'error'); }
            },

            async handleLogin() {
                if(!this.loginForm.username || !this.loginForm.password) {
                    this.showToast("请输入账号和密码", 'error'); return;
                }
                try {
                    const res = await fetch('/api/auth/login', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(this.loginForm)
                    });
                    let result = await res.text();
                    result = result.trim();
                    if (result.startsWith('ok')) {
                        const role = result.split(':')[1];
                        if (role === 'ADMIN') {
                            this.showToast("欢迎管理员！即将进入后台...", 'success');
                            setTimeout(() => window.location.href = '/admin.html', 1500);
                        } else {
                            this.showToast("登录成功！即将进入商城...", 'success');
                            setTimeout(() => window.location.href = '/shop.html', 1500);
                        }
                    } else { this.showToast(result, 'error'); }
                } catch (e) { this.showToast("服务器连接失败", 'error'); }
            },

            //  重置密码逻辑
            async handleReset() {
                // 1. 检查 resetForm 而不是 loginForm
                if(!this.resetForm.username || !this.resetForm.phone || !this.resetForm.newPassword || !this.resetForm.captcha) {
                    this.showToast("请填写完整信息", 'error'); return;
                }
                try {
                    // 2. 调用正确的接口 /reset-password
                    const res = await fetch('/api/auth/reset-password', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(this.resetForm)
                    });
                    const result = await res.text();
                    if (result === 'ok') {
                        this.showToast("重置成功！请登录", 'success');
                        setTimeout(() => this.switchToLogin(), 1500);
                    } else {
                        this.showToast(result, 'error');
                        this.refreshCaptcha();
                    }
                } catch (e) { this.showToast("服务器错误", 'error'); }
            }
        }
    }).mount('#app');
