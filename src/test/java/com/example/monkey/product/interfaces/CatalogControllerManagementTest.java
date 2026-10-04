package com.example.monkey.product.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.application.ProductCatalogApplicationService;
import com.example.monkey.product.application.dto.CatalogManagementPageQueryDto;
import com.example.monkey.product.application.dto.CatalogSpuResponseDto;
import com.example.monkey.product.application.dto.CatalogStatusTransitionRequestDto;
import com.example.monkey.product.application.dto.CatalogUpdateSpuRequestDto;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.interfaces.dto.Result;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogControllerManagementTest {

    private final ProductCatalogApplicationService service = mock();
    private CatalogController controller;

    @BeforeEach
    void setUp() {
        controller = new CatalogController(service);
    }

    @Test
    void managementListRequiresProductManagementAndDelegatesThePageQuery() {
        PageResponseDto<CatalogSpuResponseDto> page = new PageResponseDto<>(List.of(), 0, 20, 0, 1, true, true);
        CatalogManagementPageQueryDto request = new CatalogManagementPageQueryDto(0, 20, ProductStatus.DRAFT, "phone");
        when(service.findManagementPage(request)).thenReturn(page);

        Result<PageResponseDto<CatalogSpuResponseDto>> result = controller.managementList(request);

        assertThat(result.data()).isSameAs(page);
        verify(service).findManagementPage(request);
    }

    @Test
    void updateAndRetireUseCanonicalSpuEndpoints() {
        CatalogUpdateSpuRequestDto update = mock(CatalogUpdateSpuRequestDto.class);
        CatalogSpuResponseDto response = mock(CatalogSpuResponseDto.class);
        when(service.updateSpu(11L, update)).thenReturn(response);
        when(service.retireSpu(11L)).thenReturn(response);

        assertThat(controller.updateSpu(11L, update).data()).isSameAs(response);
        assertThat(controller.retireSpu(11L).data()).isSameAs(response);
        verify(service).updateSpu(11L, update);
        verify(service).retireSpu(11L);
    }

    @Test
    void statusEndpointRemainsExplicit() {
        CatalogStatusTransitionRequestDto request = new CatalogStatusTransitionRequestDto(ProductStatus.UNLISTED);
        CatalogSpuResponseDto response = mock(CatalogSpuResponseDto.class);
        when(service.transitionStatus(11L, ProductStatus.UNLISTED)).thenReturn(response);

        assertThat(controller.transitionStatus(11L, request).data()).isSameAs(response);
    }
}
