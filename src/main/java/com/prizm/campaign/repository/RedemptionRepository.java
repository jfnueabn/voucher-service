package com.prizm.campaign.repository;

import com.prizm.campaign.model.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    List<Redemption> findByVoucherId(Long voucherId);

    List<Redemption> findByCampaignId(Long campaignId);

    // Redemptions that still count toward the per-user limit: a voucher voided
    // after redemption frees the slot.
    @Query("select count(r) from Redemption r, Voucher v " +
           "where v.id = r.voucherId and r.campaignId = :campaignId " +
           "and r.userId = :userId and v.status <> 'VOID'")
    long countActiveByCampaignIdAndUserId(@Param("campaignId") Long campaignId,
                                          @Param("userId") String userId);
}
