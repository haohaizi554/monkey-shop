package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FreightTemplateRepository extends JpaRepository<FreightTemplateEntity, Long> {

    List<FreightTemplateEntity> findByCarrierAndActiveTrueAndProvinceIn(
            LogisticsCarrier carrier, Collection<String> provinces);
}
