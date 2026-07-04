package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrderCustomerPort implements OrderCustomerPort {

    private final JdbcTemplate jdbcTemplate;
    private final PiiCryptoService piiCryptoService;

    public JdbcOrderCustomerPort(JdbcTemplate jdbcTemplate, PiiCryptoService piiCryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public Optional<AddressRecord> findAddressById(Long addressId) {
        return jdbcTemplate
                .query("""
                        SELECT id, user_id, receiver_name, phone, detail_address
                        FROM address
                        WHERE id = ? AND tenant_id = ? AND deleted = false
                        """, (rs, rowNum) -> toAddressRecord(rs), addressId, TenantContext.currentTenantIdOrDefault())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<BuyerRecord> findBuyerById(Long userId) {
        return jdbcTemplate
                .query("""
                        SELECT id, username, avatar
                        FROM `user`
                        WHERE id = ? AND tenant_id = ?
                        """, (rs, rowNum) -> toBuyerRecord(rs), userId, TenantContext.currentTenantIdOrDefault())
                .stream()
                .findFirst();
    }

    private AddressRecord toAddressRecord(ResultSet rs) throws SQLException {
        return new AddressRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                piiCryptoService.decrypt(rs.getString("receiver_name")),
                piiCryptoService.decrypt(rs.getString("phone")),
                piiCryptoService.decrypt(rs.getString("detail_address")));
    }

    private static BuyerRecord toBuyerRecord(ResultSet rs) throws SQLException {
        return new BuyerRecord(rs.getLong("id"), rs.getString("username"), rs.getString("avatar"));
    }
}
