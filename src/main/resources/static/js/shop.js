const { createApp } = Vue;
    createApp({
        data() {
            return {
                monkeys: [],
                toasts: [],
                user: { isLogin: false, username: '', avatar: '' },
                banners: [
                    { img: '/images/banner/1.jpg', title: '年终灵长类大促', desc: '全场金丝猴、大猩猩限时 8 折起！' },
                    { img: '/images/banner/2.jpg', title: '新到货：马达加斯加狐猴', desc: '眼神清澈，舞姿优美，适合居家饲养' },
                    { img: '/images/banner/3.jpg', title: '会员尊享服务', desc: '购买即送全套香蕉护理套餐' }
                ],

                // 筛选条件 (新增)
                filters: {
                    keyword: '',
                    minPrice: '',
                    maxPrice: '',
                    inStockOnly: false
                },

                buyModal: null,
                currentProduct: {},
                addresses: [],
                selectedAddressId: null,
                isAddingAddress: false,
                newAddr: { receiverName: '', phone: '', detailAddress: '' }
            }
        },
        computed: {
            // --- 核心：前端实时筛选逻辑 ---
            filteredMonkeys() {
                return this.monkeys.filter(m => {
                    // 1. 关键词匹配 (名称 或 品种)
                    const keyword = this.filters.keyword.toLowerCase().trim();
                    const matchKeyword = !keyword ||
                                         (m.name && m.name.toLowerCase().includes(keyword)) ||
                                         (m.breed && m.breed.toLowerCase().includes(keyword));

                    // 2. 价格区间
                    const price = m.price;
                    const min = this.filters.minPrice;
                    const max = this.filters.maxPrice;
                    const matchPrice = (min === '' || price >= min) &&
                                       (max === '' || price <= max);

                    // 3. 库存状态
                    const matchStock = !this.filters.inStockOnly || (m.stock && m.stock > 0);

                    return matchKeyword && matchPrice && matchStock;
                });
            }
        },
        mounted() {
            this.checkUser();
            this.fetchMonkeys();
            this.buyModal = new bootstrap.Modal(document.getElementById('buyModal'));
        },
        methods: {
            showToast(msg, type = 'success') {
                const id = Date.now();
                this.toasts.push({ id, msg, type, title: type === 'error' ? '操作失败' : '操作成功' });
                setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 3000);
            },

            // 重置筛选
            resetFilters() {
                this.filters = { keyword: '', minPrice: '', maxPrice: '', inStockOnly: false };
            },

            async checkUser() {
                try {
                    const res = await fetch('/api/user/me');
                    const data = await res.json();
                    if (data.isLogin) this.user = data;
                } catch (e) { console.error("获取用户失败", e); }
            },
            async fetchMonkeys() {
                try {
                    const response = await fetch('/api/monkeys');
                    this.monkeys = await response.json();
                } catch (error) { console.error('Err:', error); }
            },
            async logout() {
                await fetch('/api/user/logout', { method: 'POST' });
                window.location.href = '/';
            },

            async openBuyModal(product) {
                if (!this.user.isLogin) {
                    this.showToast("请先登录！", 'error');
                    setTimeout(() => window.location.href = '/', 1500);
                    return;
                }
                this.currentProduct = product;
                this.isAddingAddress = false;
                await this.loadAddresses();
                this.buyModal.show();
            },

            async loadAddresses() {
                const res = await fetch('/api/address');
                this.addresses = await res.json();
                const defaultAddr = this.addresses.find(a => a.isDefault === 1);
                if (defaultAddr) {
                    this.selectedAddressId = defaultAddr.id;
                } else if (this.addresses.length > 0) {
                    this.selectedAddressId = this.addresses[0].id;
                } else {
                    this.selectedAddressId = null;
                }
            },

            async saveAddressInModal() {
                if(!this.newAddr.receiverName || !this.newAddr.phone || !this.newAddr.detailAddress) {
                    this.showToast("请填写完整", 'error'); return;
                }
                try {
                    const res = await fetch('/api/address', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(this.newAddr)
                    });
                    if(await res.text() === 'ok') {
                        this.showToast("地址添加成功");
                        this.newAddr = { receiverName: '', phone: '', detailAddress: '' };
                        await this.loadAddresses();
                        this.isAddingAddress = false;
                    } else {
                        this.showToast("添加失败", 'error');
                    }
                } catch(e) {
                    this.showToast("网络错误", 'error');
                }
            },

            async submitOrder() {
                if (!this.selectedAddressId) {
                    this.showToast("请选择收货地址", 'error'); return;
                }
                try {
                    const res = await fetch('/api/orders/create', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({
                            monkeyId: this.currentProduct.id,
                            addressId: this.selectedAddressId
                        })
                    });
                    const result = await res.text();
                    if (result === 'ok') {
                        this.buyModal.hide();
                        this.showToast("支付成功！正在跳转...", 'success');
                        setTimeout(() => window.location.href = '/orders.html', 1500);
                    } else {
                        this.showToast(result.replace('error:', ''), 'error');
                    }
                } catch (e) {
                    this.showToast("网络错误", 'error');
                }
            }
        }
    }).mount('#app');
