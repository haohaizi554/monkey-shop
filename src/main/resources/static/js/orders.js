const { createApp } = Vue;
    createApp({
        data() {
            return {
                orders: [],
                toasts: [],
                actionModal: null,
                currentActionId: null,
                currentActionType: '',
                actionText: ''
            }
        },
        mounted() {
            this.fetchOrders();
            this.actionModal = new bootstrap.Modal(document.getElementById('actionModal'));
        },
        methods: {
            showToast(msg, type = 'success') {
                const id = Date.now();
                this.toasts.push({ id, msg, type, title: type === 'error' ? '操作失败' : '操作成功' });
                setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 3000);
            },

            async fetchOrders() {
                try {
                    const res = await fetch('/api/orders/my');
                    this.orders = await res.json();
                } catch (e) { console.error("获取订单失败"); }
            },

            // 打开通用模态框
            openActionModal(id, type, text) {
                this.currentActionId = id;
                this.currentActionType = type;
                this.actionText = text;
                this.actionModal.show();
            },

            async confirmAction() {
                if(!this.currentActionId) return;

                let url = '';
                if (this.currentActionType === 'receive') url = `/api/orders/receive/${this.currentActionId}`;
                else if (this.currentActionType === 'return_apply') url = `/api/orders/return/apply/${this.currentActionId}`;
                else if (this.currentActionType === 'return_ship') url = `/api/orders/return/ship/${this.currentActionId}`;

                try {
                    const res = await fetch(url, { method: 'POST' });
                    if(await res.text() === 'ok') {
                        this.showToast("操作成功");
                        this.fetchOrders();
                        this.actionModal.hide();
                    } else {
                        this.showToast("操作失败", 'error');
                    }
                } catch(e) {
                    this.showToast("网络错误", 'error');
                }
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
            }
        }
    }).mount('#app');
