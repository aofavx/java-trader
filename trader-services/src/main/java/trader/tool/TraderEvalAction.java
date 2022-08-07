package trader.tool;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;

import trader.common.beans.BeansContainer;
import trader.common.beans.Lifecycle;
import trader.common.exchangeable.Exchange;
import trader.common.exchangeable.Exchangeable;
import trader.common.exchangeable.ExchangeableTradingTimes;
import trader.common.exchangeable.MarketDayUtil;
import trader.common.util.DateUtil;
import trader.common.util.PriceUtil;
import trader.common.util.StringUtil;
import trader.common.util.StringUtil.KVPair;
import trader.service.concurrent.OrderedExecutor;
import trader.service.md.MarketDataService;
import trader.service.plugin.PluginService;
import trader.service.plugin.PluginServiceImpl;
import trader.service.repository.BORepository;
import trader.service.ta.BarServiceImpl;
import trader.service.trade.Account;
import trader.service.trade.MarketTimeService;
import trader.service.trade.Order;
import trader.service.trade.TradeConstants.AccMoney;
import trader.service.trade.TradeConstants.OdrVolume;
import trader.service.trade.TradeService;
import trader.service.tradlet.TradletGroup;
import trader.service.tradlet.TradletService;
import trader.service.util.CmdAction;
import trader.service.util.SimpleBeansContainer;
import trader.simulator.SimBORepository;
import trader.simulator.SimMarketDataService;
import trader.simulator.SimMarketTimeService;
import trader.simulator.SimOrderedExecutor;
import trader.simulator.SimScheduledExecutorService;
import trader.simulator.SimTradletService;
import trader.simulator.trade.SimTradeService;

/**
 * 回测
 */
public class TraderEvalAction implements CmdAction {
    protected PrintWriter writer;
    protected LocalDate beginDate;
    protected LocalDate endDate;

    @Override
    public String getCommand() {
        return "eval";
    }

    @Override
    public void usage(PrintWriter writer) {
        writer.println("eval -Dtrader.configFile=TRADE_XML --beginDate=YYYYMMDD --endDate=YYYYMMDD");
        writer.println("\t回测");
    }

    @Override
    public int execute(BeansContainer beansContainer, PrintWriter writer, List<KVPair> options) throws Exception
    {
        this.writer = writer;
        if ( !parseOptions(options)) {
            return 1;
        }
        //首先确定开始交易日
        LocalDate tradingDay = beginDate;
        if ( !MarketDayUtil.isMarketDay(Exchange.SHFE, tradingDay)) {
            tradingDay = MarketDayUtil.nextMarketDay(Exchange.SHFE, tradingDay);
        }
        writer.println("回测时间: "+DateUtil.date2str(beginDate)+" - "+DateUtil.date2str(endDate) +", 共 "+MarketDayUtil.getMarketDays(null, beginDate, endDate).length+" 交易日 ");
        long bt=System.currentTimeMillis();
        SimpleBeansContainer globalBeans = createGlobalBeans();
        while(!tradingDay.isAfter(endDate)) {
            List<Exchangeable> dailyInstruments  = new ArrayList<>();
            //模拟每日交易
            tradeDaily(globalBeans, tradingDay, dailyInstruments);
            tradingDay = MarketDayUtil.nextMarketDay(dailyInstruments.get(0).exchange(), tradingDay);
        }
        //输出交易统计
        long et=System.currentTimeMillis();
        writer.println("回测结束, 耗时: "+(et-bt)/1000+" s");
        return 0;
    }

    private boolean parseOptions(List<KVPair> options) {
        beginDate = null;
        for(KVPair kv:options) {
            if ( StringUtil.isEmpty(kv.v)) {
                continue;
            }
            switch(kv.k.toLowerCase()) {
            case "begindate":
                beginDate = DateUtil.str2localdate(kv.v);
                break;
            case "enddate":
                endDate = DateUtil.str2localdate(kv.v);
                break;
            }
        }
        if ( endDate==null && beginDate==null ) {
            writer.println("需要提供过滤参数");
            return false;
        }
        return true;
    }

    /**
     * 每日交易
     */
    private void tradeDaily(SimpleBeansContainer globalBeans, LocalDate tradingDay, List<Exchangeable> dayInstruments) throws Exception
    {
        //创建当日环境
        SimpleBeansContainer dailyBeans = createDailyBeans(globalBeans, tradingDay, dayInstruments);

        SimMarketTimeService mtService = dailyBeans.getBean(SimMarketTimeService.class);
        //时间片段循环
        while(mtService.nextTimePiece());
        //销毁环境
        destroyDailyBeans(globalBeans, dailyBeans);
    }

    private void doTrade(SimpleBeansContainer beansContainer) {
        SimMarketTimeService mtService = beansContainer.getBean(SimMarketTimeService.class);
        TradeService tradeService = beansContainer.getBean(TradeService.class);
        Account account = tradeService.getPrimaryAccount();
        List<Order> orders = account.getOrders();
        writer.println("--- 交易日 "+DateUtil.date2str(mtService.getTradingDay())+" ---");
        writer.println("报单:");
        for(Order order:orders) {
            LocalDateTime stateTime = DateUtil.long2datetime(order.getStateTuple().getTimestamp());
            String orderLine = String.format( "%12s %6s %8s %8s %4d %8s %12s",
                    order.getRef(),
                    order.getDirection(),
                    order.getOffsetFlags(),
                    PriceUtil.long2str(order.getLimitPrice()),
                    order.getVolume(OdrVolume.ReqVolume),
                    order.getStateTuple().getState(),
                    DateUtil.date2str(stateTime)
                    );
            writer.println( orderLine );
        }
        String accountLine = String.format("账户净值: %8s 保证金: %8s 手续费: %8s 平仓盈亏: %8s",
                PriceUtil.long2str(account.getMoney(AccMoney.Balance)),
                PriceUtil.long2str(account.getMoney(AccMoney.CurrMargin)),
                PriceUtil.long2str(account.getMoney(AccMoney.Commission)),
                PriceUtil.long2str(account.getMoney(AccMoney.CloseProfit))
                );
        writer.println(accountLine);
    }

    /**
     * 创建一些跨越交易日的服务
     */
    private SimpleBeansContainer createGlobalBeans() throws Exception
    {
        SimpleBeansContainer globalBeans = new SimpleBeansContainer();
        //插件
        PluginServiceImpl pluginService = new PluginServiceImpl();
        pluginService.setBeansContainer(globalBeans);
        globalBeans.addBean(PluginService.class, pluginService);
        pluginService.init();
        //全局的临时存储
        SimBORepository repository = new SimBORepository();
        globalBeans.addBean(BORepository.class, repository);
        return globalBeans;
    }

    /**
     * 为某个交易日创建运行环境
     */
    private SimpleBeansContainer createDailyBeans(SimpleBeansContainer globalBeans, LocalDate tradingDay, List<Exchangeable> dayInstruments )
            throws Exception
    {
        SimpleBeansContainer beansContainer = new SimpleBeansContainer(globalBeans);
        SimMarketTimeService mtService = new SimMarketTimeService();
        SimOrderedExecutor orderedExecutor = new SimOrderedExecutor();
        SimScheduledExecutorService scheduledExecutorService = new SimScheduledExecutorService();
        SimMarketDataService mdService = new SimMarketDataService();
        SimTradeService tradeService = new SimTradeService();
        BarServiceImpl barService = new BarServiceImpl();
        SimTradletService tradletService = new SimTradletService();

        beansContainer.addBean(MarketTimeService.class, mtService);
        beansContainer.addBean(OrderedExecutor.class, orderedExecutor);
        beansContainer.addBean(ScheduledExecutorService.class, scheduledExecutorService);
        beansContainer.addBean(MarketDataService.class, mdService);
        beansContainer.addBean(TradeService.class, tradeService);
        beansContainer.addBean(BarServiceImpl.class, barService);
        beansContainer.addBean(TradletService.class, tradletService);

        scheduledExecutorService.init(beansContainer);
        barService.init(beansContainer);
        tradeService.init(beansContainer);
        tradletService.init(beansContainer);

        //找到当日的交易品种
        Set<Exchangeable> mdInstruments = new TreeSet<>();
        mdInstruments.addAll(mdService.getSubscriptions());
        for(TradletGroup tradletGroup:tradletService.getGroups()) {
            mdInstruments.addAll(tradletGroup.getInstruments());
        }
        dayInstruments.addAll(mdInstruments);
        mdService.addSubscriptions(mdInstruments);
        mdService.init(beansContainer);

        Exchangeable mdInstrument = mdInstruments.iterator().next();
        ExchangeableTradingTimes tradingTimes = mdInstrument.exchange().getTradingTimes(mdInstrument, tradingDay);
        mtService.setTimeRanges(tradingDay, tradingTimes.getMarketTimes() );
        return beansContainer;
    }

    /**
     * 销毁单个交易日环境
     */
    private void destroyDailyBeans(SimpleBeansContainer globalBeans, SimpleBeansContainer beans) {
        for(Class beanClass:beans.getAllBeans().keySet()) {
            if ( globalBeans.getBean(beanClass)!=null ) {
                continue;
            }
            Object bean = beans.getBean(beanClass);
            if ( bean instanceof Lifecycle ) {
                ((Lifecycle)bean).destroy();
            }
        }
    }

}
