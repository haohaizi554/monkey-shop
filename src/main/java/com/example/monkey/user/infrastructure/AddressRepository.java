package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AddressRepository extends JpaRepository<Address, Long> {
    // 閺屻儲鐓囨禍铏规畱閹碘偓閺堝婀撮崸鈧?
    Page<Address> findByUserId(Long userId, Pageable pageable);

    boolean existsByUserId(Long userId);

    Optional<Address> findByIdAndUserId(Long id, Long userId);

    // 閹跺﹥鐓囨禍铏规畱閹碘偓閺堝婀撮崸鈧拋鍙ヨ礋闂堢偤绮拋?(閻劋绨拋鍓х枂閺備即绮拋銈呭閻ㄥ嫰鍣哥純?
    default void clearDefault(Long userId) {
        clearDefault(userId, TenantContext.currentTenantIdOrDefault());
    }

    @Modifying
    @Transactional
    @Query("UPDATE Address a SET a.isDefault = 0 WHERE a.userId = ?1 AND a.tenantId = ?2 AND a.deleted = false")
    void clearDefault(Long userId, Long tenantId);
}
