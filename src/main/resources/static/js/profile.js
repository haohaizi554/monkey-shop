const { createApp } = Vue;
    createApp({
        data() {
            return {
                currentTab: 'info',
                user: {},
                addresses: [],
                newAddr: { receiverName: '', phone: '', detailAddress: '' },
                security: { phone: '', captcha: '', newPassword: '' },
                captchaUrl: '/api/user/captcha?t=' + Date.now(),

                // 弹窗状态
                addrModal: null,
                deleteModal: null,
                pendingDeleteId: null, // 待删除的ID
                toasts: [] // 消息队列
            }
        },
        mounted() {
            this.fetchProfile();
            this.fetchAddresses();
            this.addrModal = new bootstrap.Modal(document.getElementById('addressModal'));
            this.deleteModal = new bootstrap.Modal(document.getElementById('deleteModal'));
        },
        methods: {
            // --- Toast 工具 ---
            showToast(msg, type = 'success') {
                const id = Date.now();
                this.toasts.push({ id, msg, type, title: type === 'error' ? '操作失败' : '操作成功' });
                setTimeout(() => {
                    this.toasts = this.toasts.filter(t => t.id !== id);
                }, 3000);
            },

            async fetchProfile() {
                const res = await fetch('/api/user/profile');
                const data = await res.json();
                if(data.isLogin) this.user = data;
                else window.location.href = '/shop.html';
            },
            async fetchAddresses() {
                const res = await fetch('/api/address');
                this.addresses = await res.json();
            },

            // 上传头像
            async handleAvatarUpload(e) {
                const file = e.target.files[0];
                if(!file) return;
                const formData = new FormData();
                formData.append('file', file);
                formData.append('type', 'avatar');

                const uploadRes = await fetch('/api/upload', { method: 'POST', body: formData });
                const path = await uploadRes.text();
                if(path.startsWith('error')) {
                    this.showToast(path, 'error');
                    return;
                }

                const updateRes = await fetch('/api/user/update-avatar', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                    body: 'avatarPath=' + encodeURIComponent(path)
                });

                if(await updateRes.text() === 'ok') {
                    this.user.avatar = path;
                    this.showToast("头像修改成功！");
                }
            },

            // 地址相关
            showAddAddressModal() {
                this.newAddr = { receiverName: '', phone: '', detailAddress: '' };
                this.addrModal.show();
            },
            async saveAddress() {
                if(!this.newAddr.receiverName || !this.newAddr.phone || !this.newAddr.detailAddress) {
                    this.showToast("请填写完整", 'error'); return;
                }
                const res = await fetch('/api/address', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(this.newAddr)
                });
                if(await res.text() === 'ok') {
                    this.addrModal.hide();
                    this.showToast("地址保存成功");
                    this.fetchAddresses();
                }
            },
            async setAsDefault(id) {
                await fetch(`/api/address/set-default/${id}`, { method: 'POST' });
                this.showToast("已设为默认地址");
                this.fetchAddresses();
            },

            // --- 删除逻辑 (改用模态框) ---
            openDeleteModal(id) {
                this.pendingDeleteId = id; // 暂存ID
                this.deleteModal.show();   // 显示弹窗
            },
            async confirmDelete() {
                if(!this.pendingDeleteId) return;
                await fetch(`/api/address/${this.pendingDeleteId}`, { method: 'DELETE' });
                this.deleteModal.hide();
                this.showToast("地址已删除");
                this.fetchAddresses();
            },

            // 安全设置
            refreshCaptcha() {
                this.captchaUrl = '/api/user/captcha?t=' + Date.now();
            },
            async updatePassword() {
                if(!this.security.phone || !this.security.captcha || !this.security.newPassword) {
                    this.showToast("请填写完整信息", 'error'); return;
                }

                try {
                    const res = await fetch('/api/user/update-password', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(this.security)
                    });
                    const result = await res.text();

                    if(result === 'ok') {
                        this.showToast("密码修改成功！请重新登录");
                        setTimeout(() => window.location.href = '/', 1500);
                    } else {
                        this.showToast(result, 'error');
                        this.refreshCaptcha();
                    }
                } catch(e) {
                    this.showToast("网络错误", 'error');
                }
            }
        }
    }).mount('#app');
