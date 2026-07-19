package com.example.monkey.tenant.domain;

import java.util.List;

@FunctionalInterface
public interface ActiveTenantReader {

    List<Long> findActiveTenantIds();
}
