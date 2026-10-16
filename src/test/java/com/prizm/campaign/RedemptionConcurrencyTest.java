package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Not @Transactional: each redeem must commit on its own thread for the
// row locks to matter. Fixtures are cleaned up after each test.
@SpringBootTest
class RedemptionConcurrencyTest {

    private static final long CAMPAIGN_ID = 200L;
    private static final int ATTEMPTS = 8;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @MockBean
    private AuditClient auditClient;

    @AfterEach
    void cleanUp() {
        redemptionRepository.deleteAll(redemptionRepository.findByCampaignId(CAMPAIGN_ID));
        voucherRepository.deleteAll(voucherRepository.findByCampaignId(CAMPAIGN_ID));
        campaignRepository.deleteById(CAMPAIGN_ID);
    }

    @Test
    void parallelRedeemsBySameUserCannotExceedLimit() throws Exception {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setName("Concurrency test");
        c.setClientCode("TEST");
        c.setTotalStock(ATTEMPTS);
        c.setRemainingStock(ATTEMPTS);
        c.setActive(true);
        c.setMaxRedemptionsPerUser(1);
        campaignRepository.save(c);

        for (int i = 1; i <= ATTEMPTS; i++) {
            Voucher v = new Voucher();
            v.setId(2000L + i);
            v.setCampaignId(CAMPAIGN_ID);
            v.setCode("T200-" + i);
            v.setStatus("ACTIVE");
            voucherRepository.save(v);
        }

        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedeemResponse>> results = new ArrayList<>();
        for (int i = 1; i <= ATTEMPTS; i++) {
            String code = "T200-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return voucherService.redeem(code, "racer");
            }));
        }
        start.countDown();

        int ok = 0;
        for (Future<RedeemResponse> f : results) {
            if ("OK".equals(f.get().getResult())) {
                ok++;
            }
        }
        pool.shutdown();

        assertEquals(1, ok);
        assertEquals(1, redemptionRepository.findByCampaignId(CAMPAIGN_ID).size());
        assertEquals(ATTEMPTS - 1, campaignRepository.findById(CAMPAIGN_ID).get().getRemainingStock());
    }
}
