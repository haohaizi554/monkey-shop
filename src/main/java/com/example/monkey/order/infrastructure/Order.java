package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.ProductRecord;
import com.example.monkey.order.domain.OrderTransitionPolicy;
import com.example.monkey.shared.domain.privacy.PhoneBlindIndexTarget;
import com.example.monkey.shared.infrastructure.privacy.EncryptedStringAttributeConverter;
import com.example.monkey.shared.infrastructure.privacy.PiiBlindIndexEntityListener;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "orders")
@SQLDelete(sql = "UPDATE orders SET deleted = true, version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@EntityListeners(PiiBlindIndexEntityListener.class)
public class Order extends TenantScopedJpaEntity implements PhoneBlindIndexTarget {
    public static final String STATUS_TRANSITION_NOT_ALLOWED = OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String orderNo;

    private Long userId;

    private Long checkoutId;

    @Column(name = "checkout_sub_order_id")
    private Long checkoutSubOrderId;

    private Long shopId;

    @Column(precision = 12, scale = 2)
    private BigDecimal originalAmount;

    @Column(precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(length = 128)
    private String checkoutIdempotencyKey;

    // 婵犵數濮烽弫鍛婃叏閻戣棄鏋侀柟闂寸绾惧鏌ｉ幇顒佹儓闁搞劌鍊块幃瑙勬姜閹峰矈鍔呭┑鐐插悑閻楃娀寮婚敐澶嬪亜闁绘挸绨奸崰濠囨⒑缂佹ɑ鐓ユ俊顐ｇ懇楠炲牓濡搁妷顔藉瘜闁荤姴娲╁鎾寸珶閺囩喍绻嗛柣鎰典簻閳ь剚鍨垮畷鐟懊洪鍛画闂侀潧顦弲鈺呭极閸パ€鏀介柛灞剧矤閻掗箖姊洪崡鐐村枠闁哄本娲濈粻娑氣偓锝庝簽娴犳儳鈹戦悩顔肩仾闁搞劏鍩栫粚杈ㄧ節閸ャ劌鈧攱銇勮箛鎾愁仱闁稿鎹囧浠嬵敇閻愭妲烽梻浣侯攰閹活亞绮婚幋鐘差棜鐟滅増甯楅悡娑㈡煕閵夈垺娅呴柛鎾讳憾閺?(闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣捣閻棗銆掑锝呬壕濡ょ姷鍋為悧鐘汇€侀弴銏℃櫇闁逞屽墰婢规洝銇愰幒鎾跺幗闂佺粯姊婚崢褎绂嶆导瀛樼厽闁哄倹瀵чˉ鐐烘煙娓氬灝濡兼い顏勫暟閹风娀鐓鐑嗘闂?
    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String buyerName;

    private String buyerAvatar;

    // 闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣捣閻棗銆掑锝呬壕濡ょ姷鍋為〃鍛粹€﹂妸鈺侀唶婵犻潧鐗忓畷鍫曟⒑绾懎浜归悶娑栧劦瀹曞綊骞嗚濡插牊淇婇娑氱煁婵☆偄鍟悾鐑藉Ω閳哄﹥鏅ｉ梺缁樼憿閸嬫捇鏌涢悢閿嬪枠闁哄矉缍侀幃銏ゅ传閵壯呭帒闂備焦鎮堕崝灞斤耿闁秴绀嗛柟鐑樻⒐鐎氭碍绻涢弶鎴剱妞ゎ偄绉瑰娲濞戞氨鐣鹃梺鍝勬噺缁挸顕ｉ幓鎺嗘斀閻庯綆鍋€閹锋椽姊婚崒姘卞缂佸鎸婚弲鍫曞即閵忥紕鍘甸梺鍛婂姇瀵爼骞戦敐澶嬬厓?
    private Long productId;
    private String productName;
    private String productImage;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    private String description;

    // 闂傚倸鍊搁崐鎼佸磹閹间礁纾瑰瀣椤愯姤鎱ㄥ鍡楀⒒闁绘帟妫勯埞鎴︽偐瀹曞浂鏆￠梺鎶芥敱濡啴寮诲☉銏犲嵆闁靛鍎伴懜顏堟⒑缂佹ɑ鐓ユ俊顐ｇ懄缁岃鲸绻濋崶鑸垫櫖濠电偛妫欑敮鈺呭礉閸涘瓨鈷戠紓浣姑粭鍌滅磼椤旂晫鎳囩€殿喛顕ч埥澶愬閻樼數鏉搁梻鍌氬€搁悧濠勭矙閹寸姷涓嶅┑鐘崇閳锋垶鎱ㄩ悷鐗堟悙闁逞屽墯閸旀瑥鐣烽幋锕€绠荤紓鍫㈠Х缁犳岸姊虹紒妯哄Е濞存粍绮撻崺鈧い鎺嶈兌婢х數鈧娲樼换鍫ョ嵁鐎ｎ喗鏅濋柍褜鍓涙竟鏇°亹閹烘挾鍘搁梺鎼炲劗閺呮盯宕滈柆宥嗙厱?
    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String receiverName;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String receiverPhone;

    @Column(name = "receiver_phone_hmac", columnDefinition = "CHAR(64)")
    private String receiverPhoneHmac;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 2048)
    private String addressSnapshot;

    private LocalDateTime shippingTime;

    private String status;

    @Column(nullable = false)
    private boolean deleted;

    @Column(nullable = false)
    private boolean userHidden;

    @Column(nullable = false)
    private boolean piiAnonymized;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "create_time", insertable = false, updatable = false)
    private LocalDateTime createTime;

    public static Order place(String orderNo, BuyerRecord buyer, ProductRecord product, AddressRecord address) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(buyer.id());
        order.setBuyerName(buyer.username());
        order.setBuyerAvatar(buyer.avatar());
        order.setProductId(product.id());
        order.setProductName(product.name());
        order.setProductImage(product.imageUrl());
        order.setPrice(product.price());
        order.setDescription(product.description());
        order.setReceiverName(address.receiverName());
        order.setReceiverPhone(address.phone());
        order.setAddressSnapshot(address.detailAddress());
        order.markPendingPayment();
        return order;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getCheckoutId() {
        return checkoutId;
    }

    public void setCheckoutId(Long checkoutId) {
        this.checkoutId = checkoutId;
    }

    public Long getCheckoutSubOrderId() {
        return checkoutSubOrderId;
    }

    public void setCheckoutSubOrderId(Long checkoutSubOrderId) {
        this.checkoutSubOrderId = checkoutSubOrderId;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public String getCheckoutIdempotencyKey() {
        return checkoutIdempotencyKey;
    }

    public void setCheckoutIdempotencyKey(String checkoutIdempotencyKey) {
        this.checkoutIdempotencyKey = checkoutIdempotencyKey;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public String getBuyerAvatar() {
        return buyerAvatar;
    }

    public void setBuyerAvatar(String buyerAvatar) {
        this.buyerAvatar = buyerAvatar;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductImage() {
        return productImage;
    }

    public void setProductImage(String productImage) {
        this.productImage = productImage;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverPhoneHmac() {
        return receiverPhoneHmac;
    }

    public void setReceiverPhoneHmac(String receiverPhoneHmac) {
        this.receiverPhoneHmac = receiverPhoneHmac;
    }

    @Override
    public String phoneValueForBlindIndex() {
        return receiverPhone;
    }

    @Override
    public void setPhoneBlindIndex(String blindIndex) {
        this.receiverPhoneHmac = blindIndex;
    }

    public String getAddressSnapshot() {
        return addressSnapshot;
    }

    public void setAddressSnapshot(String addressSnapshot) {
        this.addressSnapshot = addressSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean hasStatus(OrderStatus status) {
        return status.matches(this.status);
    }

    public void markStatus(OrderStatus status) {
        this.status = status.label();
    }

    public void markPaid() {
        markStatus(OrderStatus.PAID);
    }

    public void markPendingPayment() {
        markStatus(OrderStatus.PENDING_PAYMENT);
    }

    public void ship(LocalDateTime shippingTime) {
        transition(OrderEvent.SHIP);
        this.shippingTime = shippingTime;
    }

    public void markShipped(LocalDateTime shippingTime) {
        markStatus(OrderStatus.SHIPPED);
        this.shippingTime = shippingTime;
    }

    public void receive() {
        transition(OrderEvent.RECEIVE);
    }

    public void requestReturn() {
        transition(OrderEvent.REQUEST_RETURN);
    }

    public void approveReturn() {
        transition(OrderEvent.APPROVE_RETURN);
    }

    public void shipReturn() {
        transition(OrderEvent.SHIP_RETURN);
    }

    public void requireRefundable() {
        requireTransition(OrderEvent.REFUND);
    }

    public void refund() {
        transition(OrderEvent.REFUND);
    }

    public void hideFromUser() {
        userHidden = true;
    }

    public boolean shouldRestoreStockOnDelete() {
        return !hasStatus(OrderStatus.COMPLETED) && !hasStatus(OrderStatus.REFUNDED);
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isUserHidden() {
        return userHidden;
    }

    public void setUserHidden(boolean userHidden) {
        this.userHidden = userHidden;
    }

    public boolean isPiiAnonymized() {
        return piiAnonymized;
    }

    public void setPiiAnonymized(boolean piiAnonymized) {
        this.piiAnonymized = piiAnonymized;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getShippingTime() {
        return shippingTime;
    }

    public void setShippingTime(LocalDateTime shippingTime) {
        this.shippingTime = shippingTime;
    }

    private void transition(OrderEvent event) {
        markStatus(requireTransition(event));
    }

    private OrderStatus requireTransition(OrderEvent event) {
        OrderStatus currentStatus;
        try {
            currentStatus = OrderStatus.fromStoredValue(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(STATUS_TRANSITION_NOT_ALLOWED);
        }
        return OrderTransitionPolicy.nextStatus(currentStatus, event)
                .orElseThrow(() -> new IllegalStateException(STATUS_TRANSITION_NOT_ALLOWED));
    }
}
