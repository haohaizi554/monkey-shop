package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MembershipProductPriceContextResolverTest {

    @Test
    void anonymousAndBasicUsersCannotClaimMemberPricing() {
        MembershipStore membershipStore = mock();
        MembershipProductPriceContextResolver resolver = new MembershipProductPriceContextResolver(membershipStore);

        assertThat(resolver.resolve(null, " cn-bj ")).satisfies(context -> {
            assertThat(context.userIdentity()).isEqualTo("ANONYMOUS");
            assertThat(context.region()).isEqualTo("CN-BJ");
            assertThat(context.isMember()).isFalse();
        });
        verifyNoInteractions(membershipStore);

        MemberProfile basicProfile = mock();
        when(basicProfile.level()).thenReturn(MembershipLevel.BASIC);
        when(membershipStore.findProfile(7L)).thenReturn(Optional.of(basicProfile));

        assertThat(resolver.resolve(7L, "cn-sh").isMember()).isFalse();
    }

    @Test
    void nonBasicMembershipIsResolvedServerSide() {
        MembershipStore membershipStore = mock();
        MemberProfile goldProfile = mock();
        when(goldProfile.level()).thenReturn(MembershipLevel.GOLD);
        when(membershipStore.findProfile(7L)).thenReturn(Optional.of(goldProfile));
        MembershipProductPriceContextResolver resolver = new MembershipProductPriceContextResolver(membershipStore);

        assertThat(resolver.resolve(7L, "CN-BJ")).satisfies(context -> {
            assertThat(context.userIdentity()).isEqualTo("MEMBER");
            assertThat(context.region()).isEqualTo("CN-BJ");
            assertThat(context.isMember()).isTrue();
        });
    }
}
