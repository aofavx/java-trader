package trader.service.ta.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.Num;

import trader.service.ta.FutureBar;
import trader.service.ta.LongNum;

/**
 * Average price indicator.
 */
public class OpenIntIndicator extends CachedIndicator<Num> {

    public OpenIntIndicator(BarSeries series) {
        super(series);
    }

    @Override
    protected Num calculate(int index) {
        FutureBar bar = (FutureBar)getBarSeries().getBar(index);
        return LongNum.valueOf(bar.getOpenInt());
    }
}
