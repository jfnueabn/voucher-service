package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.VoucherRepository;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Each test runs in a transaction that is rolled back, so fixtures and
// redemptions don't leak between tests.
@SpringBootTest
@Transactional
class RedemptionLimitTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @MockBean
    private AuditClient auditClient;

    @Test
    void seededCampaignsDefaultToLimitOfTwo() {
        for (Campaign c : campaignRepository.findAll()) {
            assertEquals(2, c.getMaxRedemptionsPerUser(), "campaign " + c.getId());
        }
    }

    @Test
    void user99VoidedRedemptionDoesNotCount() {
        // user-99 has RAYA-0004 (redeemed) and RAYA-0005 (since voided): 1 of 2 used
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-99").getResult());

        RedeemResponse res = voucherService.redeem("RAYA-0003", "user-99");
        assertEquals("FAILED", res.getResult());
        assertEquals("Per-user redemption limit reached for this campaign (2 of 2)", res.getMessage());
    }

    @Test
    void redemptionBeyondLimitIsRejectedWithoutSideEffects() {
        campaign(100L, 2);
        voucher(1001L, 100L, "T100-1");
        voucher(1002L, 100L, "T100-2");
        voucher(1003L, 100L, "T100-3");

        assertEquals("OK", voucherService.redeem("T100-1", "alice").getResult());
        assertEquals("OK", voucherService.redeem("T100-2", "alice").getResult());

        RedeemResponse res = voucherService.redeem("T100-3", "alice");
        assertEquals("FAILED", res.getResult());
        assertTrue(res.getMessage().contains("limit"));

        assertEquals("ACTIVE", voucherRepository.findByCode("T100-3").getStatus());
        assertEquals(8, campaignRepository.findById(100L).get().getRemainingStock());
    }

    @Test
    void otherUsersAreUnaffected() {
        campaign(100L, 1);
        voucher(1001L, 100L, "T100-1");
        voucher(1002L, 100L, "T100-2");

        assertEquals("OK", voucherService.redeem("T100-1", "alice").getResult());
        assertEquals("OK", voucherService.redeem("T100-2", "bob").getResult());
    }

    @Test
    void limitIsPerCampaign() {
        campaign(100L, 1);
        voucher(1001L, 100L, "T100-1");

        assertEquals("OK", voucherService.redeem("T100-1", "alice").getResult());
        assertEquals("OK", voucherService.redeem("MRDK-0001", "alice").getResult());
    }

    @Test
    void campaignCanSetItsOwnLimit() {
        campaign(100L, 3);
        for (long i = 1; i <= 4; i++) {
            voucher(1000L + i, 100L, "T100-" + i);
        }

        assertEquals("OK", voucherService.redeem("T100-1", "alice").getResult());
        assertEquals("OK", voucherService.redeem("T100-2", "alice").getResult());
        assertEquals("OK", voucherService.redeem("T100-3", "alice").getResult());
        assertEquals("FAILED", voucherService.redeem("T100-4", "alice").getResult());
    }

    @Test
    void voidingARedeemedVoucherFreesTheSlot() {
        campaign(100L, 1);
        voucher(1001L, 100L, "T100-1");
        voucher(1002L, 100L, "T100-2");

        assertEquals("OK", voucherService.redeem("T100-1", "alice").getResult());
        assertEquals("FAILED", voucherService.redeem("T100-2", "alice").getResult());

        voucherService.voidVoucher("T100-1");

        assertEquals("OK", voucherService.redeem("T100-2", "alice").getResult());
    }

    private void campaign(Long id, int maxPerUser) {
        Campaign c = new Campaign();
        c.setId(id);
        c.setName("Limit test " + id);
        c.setClientCode("TEST");
        c.setTotalStock(10);
        c.setRemainingStock(10);
        c.setActive(true);
        c.setMaxRedemptionsPerUser(maxPerUser);
        campaignRepository.save(c);
    }

    private void voucher(Long id, Long campaignId, String code) {
        Voucher v = new Voucher();
        v.setId(id);
        v.setCampaignId(campaignId);
        v.setCode(code);
        v.setStatus("ACTIVE");
        voucherRepository.save(v);
    }
}
