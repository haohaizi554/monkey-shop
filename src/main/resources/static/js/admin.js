const { createApp } = Vue;

    createApp({
        data() {
            return {
                showAuthOverlay: true,
                loginForm: { username: '', password: '' },
                currentTab: 'dashboard',

                // 数据源
                monkeys: [],
                orders: [],
                stats: {},
                toasts: [],

                // 表单与模态框状态
                modalMode: 'add',
                form: { id: null, name: '', breed: '', price: 0, stock: 10, description: '', imageUrl: '' },
                productModal: null,
                deleteModal: null,
                actionModal: null,

                // 操作暂存 ID
                pendingDeleteId: null,
                deleteType: 'product',
                currentActionId: null,
                currentActionType: '',
                actionText: '',

                // 搜索与排序
                orderSearch: '',
                sortKey: '',
                sortAsc: false,

                // 图表相关
                rangeType: '7d',
                customStart: '',
                customEnd: '',
                chartInstance: null,
                barInstance: null
            }
        },
        computed: {
            // 前端过滤与排序订单逻辑
            filteredOrders() {
                let result = this.orders;
                // 1. 搜索
                if (this.orderSearch) {
                    const lower = this.orderSearch.toLowerCase();
                    result = result.filter(o =>
                        (o.orderNo && o.orderNo.toLowerCase().includes(lower)) ||
                        (o.buyerName && o.buyerName.toLowerCase().includes(lower)) ||
                        (o.productName && o.productName.toLowerCase().includes(lower)) ||
                        (o.price && o.price.toString().includes(lower)) ||
                        (o.createTime && o.createTime.includes(lower))
                    );
                }
                // 2. 排序
                if (this.sortKey) {
                    result.sort((a, b) => {
                        let valA = a[this.sortKey];
                        let valB = b[this.sortKey];
                        if (typeof valA === 'string') valA = valA.toLowerCase();
                        if (typeof valB === 'string') valB = valB.toLowerCase();
                        if (valA < valB) return this.sortAsc ? -1 : 1;
                        if (valA > valB) return this.sortAsc ? 1 : -1;
                        return 0;
                    });
                }
                return result;
            }
        },
        async mounted() {
            await this.checkPermission();
            this.productModal = new bootstrap.Modal(document.getElementById('productModal'));
            this.deleteModal = new bootstrap.Modal(document.getElementById('deleteModal'));
            this.actionModal = new bootstrap.Modal(document.getElementById('actionModal'));

            // 窗口调整时重绘图表
            window.addEventListener('resize', () => {
                if(this.chartInstance) this.chartInstance.resize();
                if(this.barInstance) this.barInstance.resize();
            });
        },
        methods: {
            showToast(msg, type='success') {
                const id = Date.now();
                this.toasts.push({id, msg, type});
                setTimeout(() => this.toasts = this.toasts.filter(t => t.id !== id), 3000);
            },

            switchTab(tab) {
                this.currentTab = tab;
                if (tab === 'product') this.fetchMonkeys();
                else if (tab === 'order') this.fetchOrders();
                else if (tab === 'dashboard') this.$nextTick(() => this.fetchStats());
            },

            // --- 核心业务：数据看板 ---
            setRange(type) {
                this.rangeType = type;
                if (type === 'custom') {
                    const d = new Date();
                    const year = d.getFullYear();
                    const month = (d.getMonth() + 1).toString().padStart(2, '0');
                    const day = d.getDate().toString().padStart(2, '0');
                    const today = `${year}-${month}-${day}`;
                    if (!this.customStart || !this.customEnd) {
                        this.showToast("请选择日期范围", 'error'); return;
                    }
                    if (this.customStart > today || this.customEnd > today) { this.showToast("不能选择未来日期", 'error'); return; }
                    if (this.customStart > this.customEnd) { this.showToast("开始日期不能晚于结束日期", 'error'); return; }
                    this.fetchStats(this.customStart, this.customEnd);
                } else {
                    // 快捷按钮逻辑保持不变
                    const end = new Date();
                    const start = new Date();
                    if (type === '7d') start.setDate(end.getDate() - 6);
                    if (type === '30d') start.setDate(end.getDate() - 29);
                    if (type === '1y') start.setFullYear(end.getFullYear() - 1);
                    // 这里也要用本地时间格式化，防止快捷按钮也跨天出问题
                    const formatDate = (date) => {
                        const y = date.getFullYear();
                        const m = (date.getMonth() + 1).toString().padStart(2, '0');
                        const d = date.getDate().toString().padStart(2, '0');
                        return `${y}-${m}-${d}`;
                    };
                    this.fetchStats(formatDate(start), formatDate(end));
                }
            },

            async fetchStats(start = null, end = null) {
                let url = '/api/stats/data';
                if (start && end) url += `?start=${start}&end=${end}`;
                try {
                    const res = await fetch(url);
                    this.stats = await res.json();
                    this.initCharts();
                } catch (e) {
                    console.error("获取统计失败");
                }
            },

            initCharts() {
                if (this.chartInstance) this.chartInstance.dispose();
                this.chartInstance = echarts.init(document.getElementById('trendChart'));
                this.chartInstance.setOption({
                    title: { text: '流量与订单趋势' },
                    tooltip: { trigger: 'axis' },
                    legend: { data: ['订单量', '访问量'] },
                    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
                    xAxis: { type: 'category', data: this.stats.xAxis },
                    yAxis: [
                        { type: 'value', name: '订单量', position: 'left', axisLine: { show: true }, axisLabel: { formatter: '{value}' } },
                        { type: 'value', name: '访问量', position: 'right', axisLine: { show: true }, axisLabel: { formatter: '{value}' }, splitLine: { show: false } }
                    ],
                    series: [
                        { name: '订单量', type: 'line', yAxisIndex: 0, data: this.stats.seriesOrder, smooth: true, itemStyle: { color: '#3b82f6' } },
                        { name: '访问量', type: 'line', yAxisIndex: 1, data: this.stats.seriesVisit, smooth: true, itemStyle: { color: '#10b981' } }
                    ]
                });

                if (this.barInstance) this.barInstance.dispose();
                this.barInstance = echarts.init(document.getElementById('barChart'));
                this.barInstance.setOption({
                    title: { text: '销售额 (GMV)' },
                    tooltip: { trigger: 'axis' },
                    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
                    xAxis: { type: 'category', data: this.stats.xAxis },
                    yAxis: { type: 'value' },
                    series: [{ name: '销售额', type: 'bar', data: this.stats.seriesGmv, itemStyle: { color: '#8b5cf6' } }]
                });
            },

            // --- 核心业务：图片上传与裁剪 ---
            async uploadProductImage(e) {
                const file = e.target.files[0];
                if (!file) return;

                const formData = new FormData();
                formData.append('file', file);
                formData.append('type', 'product');

                try {
                    const res = await fetch('/api/upload', { method: 'POST', body: formData });
                    const path = await res.text();

                    if (path.startsWith('error')) {
                        this.showToast(path.replace('error:', ''), 'error');
                    } else if (path.startsWith('cropped:')) {
                        this.form.imageUrl = path.replace('cropped:', '');
                        this.showToast("图片已自动裁剪适配", 'warning');
                    } else if (path.startsWith('ok:')) {
                        this.form.imageUrl = path.replace('ok:', '');
                        this.showToast("上传成功");
                    } else {
                        this.form.imageUrl = path;
                        this.showToast("上传成功");
                    }
                } catch (e) {
                    this.showToast("上传失败", 'error');
                }
            },

            // --- 权限管理 ---
            async checkPermission() {
                try {
                    const res = await fetch('/api/user/me');
                    const data = await res.json();
                    if (data.isLogin && data.identity === 'ADMIN') {
                        this.showAuthOverlay = false;
                        this.fetchStats();
                    } else {
                        this.showAuthOverlay = true;
                    }
                } catch (e) {
                    this.showAuthOverlay = true;
                }
            },

            async handleAdminLogin() {
                if (!this.loginForm.username || !this.loginForm.password) {
                    this.showToast("请输入账号密码", 'error');
                    return;
                }
                try {
                    const res = await fetch('/api/auth/login', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(this.loginForm)
                    });
                    let result = await res.text();
                    if (result.trim() === 'ok:ADMIN') {
                        this.showToast("登录成功");
                        this.showAuthOverlay = false;
                        this.fetchStats();
                        this.loginForm = { username: '', password: '' };
                    } else {
                        this.showToast("登录失败或无权限", 'error');
                    }
                } catch (e) {
                    this.showToast("网络错误", 'error');
                }
            },

            // --- 商品 CRUD ---
            async fetchMonkeys() {
                const res = await fetch('/api/monkeys');
                this.monkeys = await res.json();
            },

            openModal(mode, item = null) {
                this.modalMode = mode;
                this.form = mode === 'edit' && item
                    ? { ...item }
                    : { id: null, name: '', breed: '', price: 0, stock: 10, description: '', imageUrl: '' };
                this.productModal.show();
            },

            async saveProduct() {
                if (!this.form.name || !this.form.price) {
                    this.showToast("请填写完整", 'error');
                    return;
                }
                const url = this.modalMode === 'add' ? '/api/monkeys/add' : '/api/monkeys/update';
                const res = await fetch(url, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(this.form)
                });
                if (await res.text() === 'ok') {
                    this.showToast("保存成功");
                    this.productModal.hide();
                    this.fetchMonkeys();
                } else {
                    this.showToast("保存失败", 'error');
                }
            },

            openDeleteModal(id, type) {
                this.pendingDeleteId = id;
                this.deleteType = type;
                this.deleteModal.show();
            },

            async confirmDelete() {
                if (!this.pendingDeleteId) return;
                const url = this.deleteType === 'product'
                    ? `/api/monkeys/${this.pendingDeleteId}`
                    : `/api/orders/${this.pendingDeleteId}`;

                const res = await fetch(url, { method: 'DELETE' });

                if (await res.text() === 'ok') {
                    this.showToast(this.deleteType === 'product' ? "已下架" : "订单已删除");
                    this.deleteType === 'product' ? this.fetchMonkeys() : this.fetchOrders();
                    this.deleteModal.hide();
                } else {
                    this.showToast("删除失败", 'error');
                }
            },

            // --- 订单管理逻辑 ---
            async fetchOrders() {
                const res = await fetch('/api/orders/all');
                this.orders = await res.json();
            },

            openActionModal(id, type, text) {
                this.currentActionId = id;
                this.currentActionType = type;
                this.actionText = text;
                this.actionModal.show();
            },

            async confirmAction() {
                if(!this.currentActionId) return;
                let url = '';

                if (this.currentActionType === 'ship') url = `/api/orders/ship/${this.currentActionId}`;
                else if (this.currentActionType === 'approve_return') url = `/api/orders/return/approve/${this.currentActionId}`;
                else if (this.currentActionType === 'confirm_return') url = `/api/orders/return/confirm/${this.currentActionId}`;

                const res = await fetch(url, { method: 'POST' });
                if(await res.text() === 'ok') {
                    this.showToast("操作成功");
                    this.fetchOrders();
                    this.actionModal.hide();
                } else {
                    this.showToast("操作失败", 'error');
                }
            },

            sortOrders(key) {
                if (this.sortKey === key) this.sortAsc = !this.sortAsc;
                else { this.sortKey = key; this.sortAsc = false; }
            },

            getSortIcon(key) {
                if (this.sortKey !== key) return 'bi bi-arrow-down-up sort-icon';
                return this.sortAsc ? 'bi bi-arrow-up sort-icon active' : 'bi bi-arrow-down sort-icon active';
            },

            getStatusClass(status) {
                if (status === '已发货') return 'status-shipped';
                if (status === '已完成') return 'status-done';
                if (status.includes('退货') || status === '已退款') return 'status-return';
                return 'status-paid';
            },

            formatDate(dateStr) {
                if(!dateStr) return '';
                return new Date(dateStr).toLocaleString();
            },

            async logout() {
                await fetch('/api/user/logout', { method: 'POST' });
                this.showAuthOverlay = true;
                this.showToast("已退出登录");
            }
        }
    }).mount('#app');
