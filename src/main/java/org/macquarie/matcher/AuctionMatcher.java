package org.macquarie.matcher;

import org.macquarie.core.OrderBook;
import org.macquarie.model.AuctionResult;

public class AuctionMatcher {

    private final OrderBook orderBook;

    public AuctionMatcher(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    public AuctionResult calculateAuctionMatch() {
        int minTick = orderBook.getMinActiveTick();
        int maxTick = orderBook.getMaxActiveTick();

        if (minTick > maxTick) {
            return new AuctionResult(0.0, 0);
        }

        long cumSellVolume = 0;
        long cumBuyVolume = orderBook.getTotalBuyVolume();

        long maxVolume = 0;
        int minBestTick = -1;
        int maxBestTick = -1;

        long buyVolAtMinTick = 0;
        long sellVolAtMaxTick = 0;

        for (int i = minTick; i <= maxTick; i++) {
            long currentSellVol = orderBook.getSellVolumeAt(i);
            long currentBuyVol = orderBook.getBuyVolumeAt(i);

            if (currentSellVol == 0 && currentBuyVol == 0 && cumSellVolume == 0) {
                cumBuyVolume -= currentBuyVol;
                continue;
            }

            cumSellVolume += currentSellVol;
            long matchedVolume = Math.min(cumBuyVolume, cumSellVolume);

            if (matchedVolume > maxVolume) {
                maxVolume = matchedVolume;
                minBestTick = i;
                maxBestTick = i;
                buyVolAtMinTick = cumBuyVolume;
                sellVolAtMaxTick = cumSellVolume;
            } else if (matchedVolume == maxVolume && maxVolume > 0) {
                maxBestTick = i;
                sellVolAtMaxTick = cumSellVolume;
            }

            cumBuyVolume -= currentBuyVol;
        }

        if (maxVolume == 0 || minBestTick == -1) {
            return new AuctionResult(0.0, 0);
        }

        int finalTick = resolveBestTick(minBestTick, maxBestTick, buyVolAtMinTick, sellVolAtMaxTick, maxVolume);
        return new AuctionResult(orderBook.tickIndexToPrice(finalTick), maxVolume);
    }

    /**
     * Resolves the final clearing tick from an optimal price range using
     * standard exchange tie-breaking rules (market surplus pressure & midpoint fallback).
     */
    private int resolveBestTick(int minBestTick, int maxBestTick, long buyVolAtMinTick,
                                long sellVolAtMaxTick, long maxVolume) {
        // If there is no price range tie, return the single optimal tick
        if (minBestTick == maxBestTick) {
            return minBestTick;
        }

        // Calculate surplus (unexecuted order imbalances) at the boundaries of the range
        long buySurplusAtMin = buyVolAtMinTick - maxVolume;
        long sellSurplusAtMax = sellVolAtMaxTick - maxVolume;

        // Apply market pressure rules or fallback to midpoint
        if (buySurplusAtMin > sellSurplusAtMax) {
            return maxBestTick; // Excess buy pressure favors upper bound
        } else if (sellSurplusAtMax > buySurplusAtMin) {
            return minBestTick; // Excess sell pressure favors lower bound
        } else {
            return (minBestTick + maxBestTick) / 2; // Neutral balance takes precise midpoint
        }
    }
}