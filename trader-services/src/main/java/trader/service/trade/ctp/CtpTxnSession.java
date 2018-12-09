package trader.service.trade.ctp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import net.common.util.BufferUtil;
import net.jctp.*;
import trader.common.beans.BeansContainer;
import trader.common.exception.AppException;
import trader.common.exchangeable.Exchange;
import trader.common.exchangeable.Exchangeable;
import trader.common.exchangeable.Future;
import trader.common.exchangeable.MarketDayUtil;
import trader.common.util.DateUtil;
import trader.common.util.EncryptionUtil;
import trader.common.util.JsonUtil;
import trader.common.util.PriceUtil;
import trader.common.util.StringUtil;
import trader.service.ServiceConstants.ConnState;
import trader.service.ServiceErrorConstants;
import trader.service.event.AsyncEventService;
import trader.service.md.MarketDataService;
import trader.service.trade.*;
import trader.service.trade.spi.AbsTxnSession;
import trader.service.trade.spi.TxnSessionListener;

/**
 * CTP的交易会话实现类. 目前使用异步多线程处理模式: 在收到报单/成交回报事件后, 将事件排队到AsyncEventService中异步处理.
 */
public class CtpTxnSession extends AbsTxnSession implements TraderApiListener, ServiceErrorConstants, TradeConstants, JctpConstants{

    private static Pattern PATTERN_CONTRACT = Pattern.compile("\\w+\\d+");

    private AsyncEventService asyncEventService;
    private String brokerId;
    /**
     * 用户ID是解密之后的值
     */
    private String userId;
    private TraderApi traderApi;
    private int frontId;
    /**
     * 通过计算得到的期货公式的保证金率的调整值
     */
    private Map<Exchangeable, CThostFtdcInvestorPositionDetailField> marginByPos = null;
    private CtpTxnEventProcessor processor;
    public CtpTxnSession(BeansContainer beansContainer, Account account, TxnSessionListener listener) {
        super(beansContainer, account, listener);
        asyncEventService = beansContainer.getBean(AsyncEventService.class);
        Properties props = account.getConnectionProps();
        brokerId = props.getProperty("brokerId");
        userId= props.getProperty("userId");
        if ( EncryptionUtil.isEncryptedData(userId) ) {
            userId = new String( EncryptionUtil.symmetricDecrypt(userId), StringUtil.UTF8);
        }
        processor= new CtpTxnEventProcessor(account, listener);
    }

    @Override
    public String getProvider() {
        return PROVIDER_CTP;
    }

    @Override
    public void connect() {
        try {
            changeState(ConnState.Connecting);
            closeImpl();

            traderApi = new TraderApi();
            traderApi.setListener(this);
            traderApi.setFlowControl(true);
            traderApi.SubscribePrivateTopic(JctpConstants.THOST_TERT_QUICK);
            traderApi.SubscribePublicTopic(JctpConstants.THOST_TERT_QUICK);
            String frontUrl = account.getConnectionProps().getProperty("frontUrl");
            traderApi.Connect(frontUrl);
            logger.info(account.getId()+" connect to "+frontUrl+", TRADER API version: "+traderApi.GetApiVersion());
        }catch(Throwable t) {
            logger.error("Connect failed", t);
            changeState(ConnState.ConnectFailed);
        }
    }

    @Override
    protected void closeImpl() {
        if ( traderApi!=null ) {
            try{
                traderApi.Close();
            }catch(Throwable t) {}
            traderApi = null;
        }
        frontId = 0;
        sessionId = 0;
    }

    /**
     * 确认结算单
     */
    @Override
    public String syncConfirmSettlement() throws Exception {
        long t0 = System.currentTimeMillis();
        String settlement = null;
        CThostFtdcQrySettlementInfoConfirmField qryInfoField = new CThostFtdcQrySettlementInfoConfirmField(brokerId, userId, userId, null);
        CThostFtdcSettlementInfoConfirmField infoConfirmField = traderApi.SyncReqQrySettlementInfoConfirm(qryInfoField);
        if ( infoConfirmField!=null && !traderApi.GetTradingDay().equals(infoConfirmField.ConfirmDate) ) {
            //未确认, 需要先查询再确认
            CThostFtdcQrySettlementInfoField qryField = new CThostFtdcQrySettlementInfoField();
            qryField.BrokerID = brokerId;
            qryField.AccountID = userId;
            qryField.InvestorID = userId;
            CThostFtdcSettlementInfoField[] infoFields = traderApi.SyncAllReqQrySettlementInfo(qryField);
            if ( infoFields==null || infoFields.length==0 ){
                if ( logger.isDebugEnabled() ) {
                    logger.debug("No settlement found to confirm");
                }
            }else{ //从多个结算单查询结构拼出结算单文本, 使用GBK编码
                CThostFtdcSettlementInfoField f1 = infoFields[0];
                byte[][] rawByteArrays = new byte[infoFields.length][];
                for(int i=0;i<infoFields.length;i++) {
                    rawByteArrays[i] = infoFields[i]._rawBytes;
                }
                if ( logger.isDebugEnabled() ) {
                    logger.debug("Trading day "+f1.TradingDay+" investor "+f1.InvestorID+" settlement id: "+f1.SettlementID+" seqence no: "+f1.SequenceNo);
                }
                settlement = ( BufferUtil.getStringFromByteArrays(rawByteArrays, Offset_CThostFtdcSettlementInfoField_Content, SizeOf_TThostFtdcContentType-1));
            }
            infoConfirmField = new CThostFtdcSettlementInfoConfirmField(brokerId,userId,traderApi.GetTradingDay(),"", 0, null, null);
            CThostFtdcSettlementInfoConfirmField confirmResult = traderApi.SyncReqSettlementInfoConfirm(infoConfirmField);
            long t1 = System.currentTimeMillis();
            logger.info("Investor "+confirmResult.InvestorID+" settlement "+confirmResult.SettlementID+" is confirmed in "+(t1-t0)+" ms");
        }
        return settlement;
    }

    /**
     * 查询账户基本信息
     */
    @Override
    public long[] syncQryAccounts() throws Exception {
        long[] result = new long[AccMoney_Count];
        CThostFtdcQryTradingAccountField q = new CThostFtdcQryTradingAccountField(brokerId, userId, null, THOST_FTDC_BZTP_Future, null);
        CThostFtdcTradingAccountField r = traderApi.SyncReqQryTradingAccount(q);

        result[AccMoney_Balance] = PriceUtil.price2long(r.Balance);
        result[AccMoney_Available] = PriceUtil.price2long(r.Available);
        result[AccMoney_FrozenMargin] = PriceUtil.price2long(r.FrozenMargin);
        result[AccMoney_CurrMargin] = PriceUtil.price2long(r.CurrMargin);
        result[AccMoney_PreMargin] = PriceUtil.price2long(r.PreMargin);
        result[AccMoney_FrozenCash] = PriceUtil.price2long(r.FrozenCash);
        result[AccMoney_Commission] = PriceUtil.price2long(r.Commission);
        result[AccMoney_FrozenCommission] = PriceUtil.price2long(r.FrozenCommission);
        result[AccMoney_CloseProfit] = PriceUtil.price2long(r.CloseProfit);
        result[AccMoney_PositionProfit] = PriceUtil.price2long(r.PositionProfit);
        result[AccMoney_WithdrawQuota] = PriceUtil.price2long(r.WithdrawQuota);
        result[AccMoney_Reserve] = PriceUtil.price2long(r.Reserve);
        result[AccMoney_Deposit] = PriceUtil.price2long(r.Deposit);
        result[AccMoney_Withdraw] = PriceUtil.price2long(r.Withdraw);

        return result;
    }

    /**
     * 加载费率计算
     */
    @Override
    public String syncLoadFeeEvaluator(Collection<Exchangeable> subscriptions) throws Exception
    {
        long t0 = System.currentTimeMillis();
        TreeSet<Exchangeable> filter = new TreeSet<>(subscriptions);
        //交易所保证金率
        JsonObject brokerMarginRatio = new JsonObject();
        Map<Exchangeable, CThostFtdcExchangeMarginRateField> marginForExchange = new HashMap<>();
        JsonObject feeInfos = new JsonObject();
        {   //查询品种基本数据
            CThostFtdcInstrumentField[] rr = traderApi.SyncAllReqQryInstrument(new CThostFtdcQryInstrumentField());
            synchronized(Exchangeable.class) {
                for(CThostFtdcInstrumentField r:rr){
                    if ( logger.isDebugEnabled() ) {
                        logger.debug(r.ExchangeID+" "+r.InstrumentID+" "+r.InstrumentName);
                    }
                    if ( !r.IsTrading ) {
                        continue;
                    }
                    //忽略SR901C4700组合品种
                    if ( !Future.PATTERN.matcher(r.InstrumentID).matches() ) {
                        continue;
                    }
                    Exchangeable e = Exchangeable.fromString(r.ExchangeID,r.InstrumentID, r.InstrumentName);

                    if (!filter.isEmpty() && !filter.contains(e)) { //忽略非主力品种
                        continue;
                    }
                    JsonObject info = new JsonObject();
                    info.addProperty("priceTick", PriceUtil.price2long(r.PriceTick));
                    info.addProperty("volumeMultiple", r.VolumeMultiple);
                    feeInfos.add(e.toString(), info);
                }
            }
        }
        {//查询保证金率
            CThostFtdcQryExchangeMarginRateField f = new CThostFtdcQryExchangeMarginRateField(brokerId, null, THOST_FTDC_HF_Speculation, null);
            CThostFtdcExchangeMarginRateField[] rr = traderApi.SyncAllReqQryExchangeMarginRate(f);
            for(int i=0;i<rr.length;i++){
                CThostFtdcExchangeMarginRateField r = rr[i];
                //对于 AP这种品种直接忽略
                if ( !Future.PATTERN.matcher(r.InstrumentID).matches() ) {
                    continue;
                }
                Exchangeable e = Exchangeable.fromString(r.InstrumentID);
                JsonObject info = (JsonObject)feeInfos.get(e.toString());
                if ( info==null ){
                    continue;
                }
                double[] marginRatios = new double[MarginRatio_Count];
                marginRatios[MarginRatio_LongByMoney]= r.LongMarginRatioByMoney;
                marginRatios[MarginRatio_LongByVolume]= r.LongMarginRatioByVolume;
                marginRatios[MarginRatio_ShortByMoney]= r.ShortMarginRatioByMoney;
                marginRatios[MarginRatio_ShortByVolume]= r.ShortMarginRatioByVolume;
                info.add("marginRatios", JsonUtil.object2json(marginRatios));
                marginForExchange.put(e, r);
            }
        }
        {//查询手续费使用, 每天只加载一次
            if( subscriptions==null || subscriptions.isEmpty() ) {
                MarketDataService marketDataService = beansContainer.getBean(MarketDataService.class);
                subscriptions = marketDataService.getPrimaryContracts();
            }
            TreeSet<String> queryInstrumentIds = new TreeSet<>();
            for(Exchangeable e:subscriptions) {
                JsonObject info = (JsonObject)feeInfos.get(e.toString());
                if ( info==null ) {
                    continue;
                }
                CThostFtdcQryInstrumentCommissionRateField f = new CThostFtdcQryInstrumentCommissionRateField();
                f.BrokerID = brokerId; f.InvestorID = userId; f.InstrumentID = e.id();
                CThostFtdcInstrumentCommissionRateField r = traderApi.SyncReqQryInstrumentCommissionRate(f);
                if( r==null ) {
                    continue;
                }
                queryInstrumentIds.add(e.id());
                double[] commissionRatios = new double[CommissionRatio_Count];
                commissionRatios[CommissionRatio_OpenByMoney]= r.OpenRatioByMoney;
                commissionRatios[CommissionRatio_OpenByVolume]= r.OpenRatioByVolume;
                commissionRatios[CommissionRatio_CloseByMoney]= r.CloseRatioByMoney;
                commissionRatios[CommissionRatio_CloseByVolume]= r.CloseRatioByVolume;
                commissionRatios[CommissionRatio_CloseTodayByMoney]= r.CloseTodayRatioByMoney;
                commissionRatios[CommissionRatio_CloseTodayByVolume]= r.CloseTodayRatioByVolume;
                info.add("commissionRatios", JsonUtil.object2json(commissionRatios));
            }
            long t1 = System.currentTimeMillis();
            logger.info("从CTP加载手续费信息, 耗时 "+(t1-t0)+" ms, "+feeInfos.size()+" 品种: "+queryInstrumentIds);
        }
        if ( marginByPos!=null){
            for(Exchangeable e:marginByPos.keySet()) {
                CThostFtdcInvestorPositionDetailField pos = marginByPos.get(e);
                if ( pos.ExchMargin==0 ) {
                    continue;
                }
                CThostFtdcExchangeMarginRateField exchangeMarginRate = marginForExchange.get(e);
                if ( exchangeMarginRate==null ) {
                    continue;
                }
                double marginRatio = 0.0;
                if ( pos.ExchMargin != pos.Margin ) {
                    marginRatio = pos.Margin*exchangeMarginRate.LongMarginRatioByMoney/pos.ExchMargin;
                }
                brokerMarginRatio.addProperty(e.toString(), PriceUtil.price2str(marginRatio));
            }
        }
        JsonObject result = new JsonObject();
        result.add("feeInfos", feeInfos);
        result.add("brokerMarginRatio", brokerMarginRatio);
        if ( brokerMarginRatio.size()>0) {
            logger.info("Account "+account.getId()+" detect broker margin ratio: "+brokerMarginRatio);
        }
        return result.toString();
    }

    private static class PositionInfoTuple{
        PosDirection direction;
        int[] volumes = new int[PosVolume_Count];
        long[] money = new long[PosMoney_Count];
        List<PositionDetailImpl> details = new ArrayList<>();
    }

    @Override
    public List<Position> syncQryPositions() throws Exception
    {
        String tradingDay = traderApi.GetTradingDay();
        List<Position> positions = new ArrayList<>();
        CThostFtdcQryInvestorPositionField f = new CThostFtdcQryInvestorPositionField();
        f.BrokerID = brokerId; f.InvestorID = userId;
        CThostFtdcInvestorPositionField[] posFields= traderApi.SyncAllReqQryInvestorPosition(f);
        Map<Exchangeable, PositionInfoTuple> posInfos = new HashMap<>();
        marginByPos = new HashMap<>();
        for(int i=0;i<posFields.length;i++){
            CThostFtdcInvestorPositionField r = posFields[i];
            Exchangeable e = Exchangeable.fromString(r.ExchangeID, r.InstrumentID);
            PosDirection posDir = CtpUtil.ctp2PosDirection(r.PosiDirection);
            PositionInfoTuple posInfo = new PositionInfoTuple();
            posInfo.direction = posDir;
            posInfos.put(e, posInfo);

            posInfo.volumes[PosVolume_Position] = r.Position;
            posInfo.volumes[PosVolume_OpenVolume]= r.OpenVolume;
            posInfo.volumes[PosVolume_CloseVolume]= r.CloseVolume;
            posInfo.volumes[PosVolume_TodayPosition]= r.TodayPosition;
            posInfo.volumes[PosVolume_YdPosition]= r.YdPosition;
            posInfo.volumes[PosVolume_LongFrozen]= r.LongFrozen;
            posInfo.volumes[PosVolume_ShortFrozen]= r.ShortFrozen;

            posInfo.money[PosMoney_LongFrozenAmount] = PriceUtil.price2long(r.LongFrozenAmount);
            posInfo.money[PosMoney_ShortFrozenAmount]= PriceUtil.price2long(r.ShortFrozenAmount);
            posInfo.money[PosMoney_OpenAmount]= PriceUtil.price2long(r.OpenAmount);
            posInfo.money[PosMoney_CloseAmount]= PriceUtil.price2long(r.CloseAmount);
            posInfo.money[PosMoney_OpenCost]= PriceUtil.price2long(r.OpenCost);
            posInfo.money[PosMoney_PositionCost]= PriceUtil.price2long(r.PositionCost);
            posInfo.money[PosMoney_PreMargin]= PriceUtil.price2long(r.PreMargin);
            posInfo.money[PosMoney_UseMargin]= PriceUtil.price2long(r.UseMargin);
            posInfo.money[PosMoney_FrozenMargin]= PriceUtil.price2long(r.FrozenMargin);
            //money[PosMoney_FrozenCash]= PriceUtil.price2long(r.FrozenCash);
            posInfo.money[PosMoney_FrozenCommission]= PriceUtil.price2long(r.FrozenCommission);
            //money[PosMoney_CashIn]= PriceUtil.price2long(r.CashIn);
            posInfo.money[PosMoney_Commission] = PriceUtil.price2long(r.Commission);
            posInfo.money[PosMoney_CloseProfit]= PriceUtil.price2long(r.CloseProfit);
            posInfo.money[PosMoney_PositionProfit]= PriceUtil.price2long(r.PositionProfit);
            posInfo.money[PosMoney_PreSettlementPrice]= PriceUtil.price2long(r.PreSettlementPrice);
            posInfo.money[PosMoney_SettlementPrice]= PriceUtil.price2long(r.SettlementPrice);
            posInfo.money[PosMoney_ExchangeMargin]= PriceUtil.price2long(r.ExchangeMargin);
        }
        //从明细分别计算 多空的今昨持仓
        CThostFtdcQryInvestorPositionDetailField f2 = new CThostFtdcQryInvestorPositionDetailField();
        f2.BrokerID = brokerId;
        f2.InvestorID = userId;
        CThostFtdcInvestorPositionDetailField[] posDetailFields = traderApi.SyncAllReqQryInvestorPositionDetail(f2);
        for(int i=0;i<posDetailFields.length;i++){
            CThostFtdcInvestorPositionDetailField d= posDetailFields[i];
            Exchangeable e = Exchangeable.fromString(d.ExchangeID, d.InstrumentID);
            PositionInfoTuple posInfo = posInfos.get(e);
            OrderDirection dir = CtpUtil.ctp2OrderDirection(d.Direction);
            int volume = d.Volume;
            long margin = PriceUtil.price2long(d.Margin);
            long price= PriceUtil.price2long(d.OpenPrice);
            boolean today = StringUtil.equals(tradingDay, d.OpenDate.trim());
            switch(dir) {
            case Buy:
                if ( today ) { //今仓
                    posInfo.volumes[PosVolume_LongTodayPosition] += volume;
                }else {
                    posInfo.volumes[PosVolume_LongYdPosition] += volume;
                }
                posInfo.volumes[PosVolume_LongPosition] += volume;
                posInfo.money[PosMoney_LongUseMargin] += margin;
                break;
            case Sell:
                if ( today ) { //今仓
                    posInfo.volumes[PosVolume_ShortTodayPosition] += volume;
                }else {
                    posInfo.volumes[PosVolume_ShortYdPosition] += volume;
                }
                posInfo.volumes[PosVolume_ShortPosition] += volume;
                posInfo.money[PosMoney_ShortUseMargin] += margin;
                break;
            }
            posInfo.details.add(new PositionDetailImpl(dir.toPosDirection(), volume, price, DateUtil.str2localdate(d.OpenDate).atStartOfDay(), today));
            marginByPos.put(e, d);
        }
        for(Exchangeable e:posInfos.keySet()) {
            PositionInfoTuple posInfo = posInfos.get(e);
            positions.add(new PositionImpl((AccountImpl)account, e, posInfo.direction, posInfo.money, posInfo.volumes, posInfo.details));
        }
        return positions;
    }

    @Override
    public void asyncSendOrder(Order order) throws AppException {
        CThostFtdcInputOrderField req = new CThostFtdcInputOrderField();
        req.BrokerID = brokerId;
        req.UserID = userId;
        req.InvestorID = userId;
        req.OrderRef = order.getRef();
        req.Direction = CtpUtil.orderDirection2ctp(order.getDirection());
        req.CombOffsetFlag = CtpUtil.orderOffsetFlag2ctp(order.getOffsetFlags());
        req.OrderPriceType = CtpUtil.orderPriceType2ctp(order.getPriceType());
        req.LimitPrice = PriceUtil.long2price(order.getLimitPrice());
        req.VolumeTotalOriginal = order.getVolume(OdrVolume_ReqVolume);
        req.InstrumentID = order.getExchangeable().id();
        req.VolumeCondition = CtpUtil.orderVolumeCondition2ctp(order.getVolumeCondition());
        req.TimeCondition = THOST_FTDC_TC_GFD; //当日有效
        req.CombHedgeFlag =  STRING_THOST_FTDC_HF_Speculation; //投机
        req.ContingentCondition = THOST_FTDC_CC_Immediately; //立即触发
        req.ForceCloseReason = THOST_FTDC_FCC_NotForceClose; //强平原因: 非强平
        req.IsAutoSuspend = false;
        req.MinVolume = 1;

        listener.changeOrderState(order, new OrderStateTuple(OrderState.Submitting, OrderSubmitState.InsertSubmitting, System.currentTimeMillis()), null);
        try{
            traderApi.ReqOrderInsert(req);
            listener.changeOrderState(order, new OrderStateTuple(OrderState.Submitted, OrderSubmitState.InsertSubmitting, System.currentTimeMillis()), null);
        }catch(Throwable t) {
            logger.error("ReqOrderInsert failed: "+order, t);
            throw new AppException(t, ERRCODE_TRADE_SEND_ORDER_FAILED, "CTP "+frontId+" ReqOrderInsert failed: "+t.toString());
        }
    }

    private boolean shouldAuthenticate() {
        Properties props = account.getConnectionProps();
        return !StringUtil.isEmpty(props.getProperty("authCode"));
    }

    private void reqAuthenticate() {
        Properties props = account.getConnectionProps();
        CThostFtdcReqAuthenticateField f = new CThostFtdcReqAuthenticateField();
        f.BrokerID = props.getProperty("brokerId");
        f.AuthCode = props.getProperty("authCode");
        try{
            traderApi.ReqAuthenticate(f);
        }catch(Throwable t) {
            logger.error("ReqAuthenticate failed", t);
            changeState(ConnState.ConnectFailed);
        }
    }

    private void reqUserLogin() {
        CThostFtdcReqUserLoginField f = new CThostFtdcReqUserLoginField();
        Properties props = account.getConnectionProps();
        f.BrokerID = brokerId;
        f.UserID = userId;
        f.Password = props.getProperty("password");
        if ( EncryptionUtil.isEncryptedData(f.Password) ) {
            f.Password = new String( EncryptionUtil.symmetricDecrypt(f.Password), StringUtil.UTF8);
        }
        try{
            traderApi.ReqUserLogin(f);
        }catch(Throwable t) {
            logger.error("ReqUserLogin failed", t);
            changeState(ConnState.ConnectFailed);
        }
    }

    @Override
    public void OnFrontConnected() {
        logger.info("OnFrontConnected");
        if ( getState()==ConnState.Connecting ) {
            //login
            if ( shouldAuthenticate() ) {
                reqAuthenticate();
            }else {
                reqUserLogin();
            }
        }
    }

    @Override
    public void OnFrontDisconnected(int nReason) {
        logger.info("OnFrontDisconnected: "+nReason);
        changeState(ConnState.Disconnected);
    }

    @Override
    public void OnHeartBeatWarning(int nTimeLapse) {
        logger.info("OnHeartBeatWarning: "+nTimeLapse);
    }

    @Override
    public void OnRspAuthenticate(CThostFtdcRspAuthenticateField pRspAuthenticateField, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspAuthenticate: "+pRspAuthenticateField+" "+pRspInfo);
        if ( pRspInfo.ErrorID==0 ) {
            reqUserLogin();
        }else {
            changeState(ConnState.ConnectFailed);
        }
    }

    @Override
    public void OnRspUserLogin(CThostFtdcRspUserLoginField pRspUserLogin, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspUserLogin: "+pRspUserLogin+" "+pRspInfo);
        if ( pRspInfo.ErrorID==0 ) {
            frontId = pRspUserLogin.FrontID;
            sessionId = pRspUserLogin.SessionID;
            changeState(ConnState.Connected);
            tradingDay = DateUtil.str2localdate(pRspUserLogin.TradingDay);
            LocalDate tradingDay2 = MarketDayUtil.getTradingDay(Exchange.SHFE, LocalDateTime.now());
            if ( !tradingDay.equals(tradingDay2)) {
                logger.error("计算交易日失败, CTP: "+tradingDay+", 计算: "+tradingDay2);
            }
        }else {
            changeState(ConnState.ConnectFailed);
        }
    }

    @Override
    public void OnRspUserLogout(CThostFtdcUserLogoutField pUserLogout, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspUserLogout: "+pUserLogout+" "+pRspInfo);
        changeState(ConnState.Disconnected);
    }

    @Override
    public void OnRspUserPasswordUpdate(CThostFtdcUserPasswordUpdateField pUserPasswordUpdate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspUserPasswordUpdate: "+pUserPasswordUpdate+" "+pRspInfo);
    }

    @Override
    public void OnRspTradingAccountPasswordUpdate(CThostFtdcTradingAccountPasswordUpdateField pTradingAccountPasswordUpdate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspTradingAccountPasswordUpdate: "+pTradingAccountPasswordUpdate+" "+pRspInfo);
    }

    /**
     * 报单错误(柜台)
     */
    @Override
    public void OnRspOrderInsert(CThostFtdcInputOrderField pInputOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        asyncEventService.publishProcessorEvent(processor, CtpTxnEventProcessor.DATA_TYPE_RSP_ORDER_INSERT, pInputOrder, pRspInfo);
        logger.error("OnRspOrderInsert: "+pInputOrder+" "+pRspInfo);
    }

    @Override
    public void OnRspParkedOrderInsert(CThostFtdcParkedOrderField pParkedOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspParkedOrderInsert: "+pParkedOrder+" "+pRspInfo);
    }

    @Override
    public void OnRspParkedOrderAction(CThostFtdcParkedOrderActionField pParkedOrderAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspParkedOrderAction: "+pParkedOrderAction+" "+pRspInfo);
    }

    @Override
    public void OnRspOrderAction(CThostFtdcInputOrderActionField pInputOrderAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        asyncEventService.publishProcessorEvent(processor, CtpTxnEventProcessor.DATA_TYPE_RSP_ORDER_ACTION, pInputOrderAction, pRspInfo);
        logger.error("OnRspOrderAction: "+pInputOrderAction+" "+pRspInfo);
    }

    @Override
    public void OnRspQueryMaxOrderVolume(CThostFtdcQueryMaxOrderVolumeField pQueryMaxOrderVolume, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspQueryMaxOrderVolume: "+pQueryMaxOrderVolume+" "+pRspInfo);
    }

    @Override
    public void OnRspSettlementInfoConfirm(CThostFtdcSettlementInfoConfirmField pSettlementInfoConfirm, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspSettlementInfoConfirm: "+pSettlementInfoConfirm+" "+pRspInfo);
    }

    @Override
    public void OnRspRemoveParkedOrder(CThostFtdcRemoveParkedOrderField pRemoveParkedOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspRemoveParkedOrder: "+pRemoveParkedOrder+" "+pRspInfo);
    }

    @Override
    public void OnRspRemoveParkedOrderAction(CThostFtdcRemoveParkedOrderActionField pRemoveParkedOrderAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspRemoveParkedOrderAction: "+pRemoveParkedOrderAction+" "+pRspInfo);
    }

    @Override
    public void OnRspExecOrderInsert(CThostFtdcInputExecOrderField pInputExecOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspExecOrderInsert: "+pInputExecOrder+" "+pRspInfo);
    }

    @Override
    public void OnRspExecOrderAction(CThostFtdcInputExecOrderActionField pInputExecOrderAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspExecOrderAction: "+pInputExecOrderAction+" "+pRspInfo);
    }

    @Override
    public void OnRspForQuoteInsert(CThostFtdcInputForQuoteField pInputForQuote, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspForQuoteInsert: "+pInputForQuote+" "+pRspInfo);
    }

    @Override
    public void OnRspQuoteInsert(CThostFtdcInputQuoteField pInputQuote, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspQuoteInsert: "+pInputQuote+" "+pRspInfo);
    }

    @Override
    public void OnRspQuoteAction(CThostFtdcInputQuoteActionField pInputQuoteAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspQuoteAction: "+pInputQuoteAction+" "+pRspInfo);
    }

    @Override
    public void OnRspBatchOrderAction(CThostFtdcInputBatchOrderActionField pInputBatchOrderAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspBatchOrderAction: "+pInputBatchOrderAction+" "+pRspInfo);
    }

    @Override
    public void OnRspOptionSelfCloseInsert(CThostFtdcInputOptionSelfCloseField pInputOptionSelfClose, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspOptionSelfCloseInsert: "+pInputOptionSelfClose+" "+pRspInfo);
    }

    @Override
    public void OnRspOptionSelfCloseAction(CThostFtdcInputOptionSelfCloseActionField pInputOptionSelfCloseAction, CThostFtdcRspInfoField pRspInfo, int nRequestID,
    boolean bIsLast) {
        logger.info("OnRspOptionSelfCloseAction: "+pInputOptionSelfCloseAction+" "+pRspInfo);

    }

    @Override
    public void OnRspCombActionInsert(CThostFtdcInputCombActionField pInputCombAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.info("OnRspCombActionInsert: "+pInputCombAction+" "+pRspInfo);
    }

    @Override
    public void OnRspQryOrder(CThostFtdcOrderField pOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryOrder: "+pOrder+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryTrade(CThostFtdcTradeField pTrade, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryTrade: "+pTrade+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInvestorPosition(CThostFtdcInvestorPositionField pInvestorPosition, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInvestorPosition: "+pInvestorPosition+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryTradingAccount(CThostFtdcTradingAccountField pTradingAccount, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryTradingAccount: "+pTradingAccount+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInvestor(CThostFtdcInvestorField pInvestor, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInvestor: "+pRspInfo+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryTradingCode(CThostFtdcTradingCodeField pTradingCode, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryTradingCode: "+pTradingCode+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInstrumentMarginRate(CThostFtdcInstrumentMarginRateField pInstrumentMarginRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInstrumentMarginRate: "+pInstrumentMarginRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInstrumentCommissionRate(CThostFtdcInstrumentCommissionRateField pInstrumentCommissionRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInstrumentCommissionRate: "+pInstrumentCommissionRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryExchange(CThostFtdcExchangeField pExchange, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryExchange: "+pExchange+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryProduct(CThostFtdcProductField pProduct, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryProduct: "+pProduct+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInstrument(CThostFtdcInstrumentField pInstrument, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInstrument: "+pInstrument+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryDepthMarketData(CThostFtdcDepthMarketDataField pDepthMarketData, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryDepthMarketData: "+pDepthMarketData+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQrySettlementInfo(CThostFtdcSettlementInfoField pSettlementInfo, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQrySettlementInfo: "+pSettlementInfo+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryTransferBank(CThostFtdcTransferBankField pTransferBank, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryTransferBank: "+pTransferBank+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInvestorPositionDetail(CThostFtdcInvestorPositionDetailField pInvestorPositionDetail, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInvestorPositionDetail: "+pInvestorPositionDetail+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryNotice(CThostFtdcNoticeField pNotice, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryNotice: "+pNotice+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQrySettlementInfoConfirm(CThostFtdcSettlementInfoConfirmField pSettlementInfoConfirm, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.info("OnRspQrySettlementInfoConfirm: "+pSettlementInfoConfirm+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInvestorPositionCombineDetail(CThostFtdcInvestorPositionCombineDetailField pInvestorPositionCombineDetail, CThostFtdcRspInfoField pRspInfo, int nRequestID,
    boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInvestorPositionCombineDetail: "+pInvestorPositionCombineDetail+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryCFMMCTradingAccountKey(CThostFtdcCFMMCTradingAccountKeyField pCFMMCTradingAccountKey, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryCFMMCTradingAccountKey: "+pCFMMCTradingAccountKey+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryEWarrantOffset(CThostFtdcEWarrantOffsetField pEWarrantOffset, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryEWarrantOffset: "+pEWarrantOffset+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInvestorProductGroupMargin(CThostFtdcInvestorProductGroupMarginField pInvestorProductGroupMargin, CThostFtdcRspInfoField pRspInfo, int nRequestID,
    boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInvestorProductGroupMargin: "+pInvestorProductGroupMargin+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryExchangeMarginRate(CThostFtdcExchangeMarginRateField pExchangeMarginRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryExchangeMarginRate: "+pExchangeMarginRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryExchangeMarginRateAdjust(CThostFtdcExchangeMarginRateAdjustField pExchangeMarginRateAdjust, CThostFtdcRspInfoField pRspInfo, int nRequestID,
    boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryExchangeMarginRateAdjust: "+pExchangeMarginRateAdjust+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryExchangeRate(CThostFtdcExchangeRateField pExchangeRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryExchangeRate: "+pExchangeRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQrySecAgentACIDMap(CThostFtdcSecAgentACIDMapField pSecAgentACIDMap, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQrySecAgentACIDMap: "+pSecAgentACIDMap+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryProductExchRate(CThostFtdcProductExchRateField pProductExchRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryProductExchRate: "+pProductExchRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryProductGroup(CThostFtdcProductGroupField pProductGroup, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryProductGroup: "+pProductGroup+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryMMInstrumentCommissionRate(CThostFtdcMMInstrumentCommissionRateField pMMInstrumentCommissionRate, CThostFtdcRspInfoField pRspInfo, int nRequestID,
    boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryMMInstrumentCommissionRate: "+pMMInstrumentCommissionRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryMMOptionInstrCommRate(CThostFtdcMMOptionInstrCommRateField pMMOptionInstrCommRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryMMOptionInstrCommRate: "+pMMOptionInstrCommRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInstrumentOrderCommRate(CThostFtdcInstrumentOrderCommRateField pInstrumentOrderCommRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInstrumentOrderCommRate: "+pInstrumentOrderCommRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQrySecAgentTradingAccount(CThostFtdcTradingAccountField pTradingAccount, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQrySecAgentTradingAccount: "+pTradingAccount+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQrySecAgentCheckMode(CThostFtdcSecAgentCheckModeField pSecAgentCheckMode, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQrySecAgentCheckMode: "+pSecAgentCheckMode+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryOptionInstrTradeCost(CThostFtdcOptionInstrTradeCostField pOptionInstrTradeCost, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryOptionInstrTradeCost: "+pOptionInstrTradeCost+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryOptionInstrCommRate(CThostFtdcOptionInstrCommRateField pOptionInstrCommRate, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryOptionInstrCommRate: "+pOptionInstrCommRate+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryExecOrder(CThostFtdcExecOrderField pExecOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryExecOrder: "+pExecOrder+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryForQuote(CThostFtdcForQuoteField pForQuote, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryForQuote: "+pForQuote+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryQuote(CThostFtdcQuoteField pQuote, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryQuote: "+pQuote+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryOptionSelfClose(CThostFtdcOptionSelfCloseField pOptionSelfClose, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryOptionSelfClose: "+pOptionSelfClose+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryInvestUnit(CThostFtdcInvestUnitField pInvestUnit, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryInvestUnit: "+pInvestUnit+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryCombInstrumentGuard(CThostFtdcCombInstrumentGuardField pCombInstrumentGuard, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryCombInstrumentGuard: "+pCombInstrumentGuard+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryCombAction(CThostFtdcCombActionField pCombAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryCombAction: "+pCombAction+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryTransferSerial(CThostFtdcTransferSerialField pTransferSerial, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryTransferSerial: "+pTransferSerial+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryAccountregister(CThostFtdcAccountregisterField pAccountregister, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryAccountregister: "+pAccountregister+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspError(CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        logger.error("OnRspError: "+pRspInfo);
    }

    /**
     * 报单回报
     */
    @Override
    public void OnRtnOrder(CThostFtdcOrderField pOrder) {
        if ( pOrder.SessionID==sessionId) {
            asyncEventService.publishProcessorEvent(processor,  CtpTxnEventProcessor.DATA_TYPE_RTN_ORDER, pOrder, null);
        } else {
            logger.info("IGNORE order return from other CTP session: "+pOrder);
        }
    }

    /**
     * 成交回报
     */
    @Override
    public void OnRtnTrade(CThostFtdcTradeField pTrade) {
        asyncEventService.publishProcessorEvent(processor,  CtpTxnEventProcessor.DATA_TYPE_RTN_TRADE, pTrade, null);
    }

    /**
     * 报单错误(交易所)
     */
    @Override
    public void OnErrRtnOrderInsert(CThostFtdcInputOrderField pInputOrder, CThostFtdcRspInfoField pRspInfo) {
        asyncEventService.publishProcessorEvent(processor,  CtpTxnEventProcessor.DATA_TYPE_ERR_RTN_ORDER_INSERT, pInputOrder, pRspInfo);
    }

    /**
     * 撤单错误(交易所)
     */
    @Override
    public void OnErrRtnOrderAction(CThostFtdcOrderActionField pOrderAction, CThostFtdcRspInfoField pRspInfo) {
        if ( pOrderAction.SessionID==sessionId) {
            asyncEventService.publishProcessorEvent(processor,  CtpTxnEventProcessor.DATA_TYPE_ERR_RTN_ORDER_ACTION, pOrderAction, pRspInfo);
        }else {
            logger.info("IGNORE order action from other CTP session: "+pOrderAction);
        }
    }

    @Override
    public void OnRtnInstrumentStatus(CThostFtdcInstrumentStatusField pInstrumentStatus) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRtnInstrumentStatus: "+pInstrumentStatus);
        }
    }

    @Override
    public void OnRtnBulletin(CThostFtdcBulletinField pBulletin) {
        logger.info("OnRtnBulletin: "+pBulletin);
    }

    @Override
    public void OnRtnTradingNotice(CThostFtdcTradingNoticeInfoField pTradingNoticeInfo) {
        logger.info("OnRtnTradingNotice: "+pTradingNoticeInfo);
    }

    @Override
    public void OnRtnErrorConditionalOrder(CThostFtdcErrorConditionalOrderField pErrorConditionalOrder) {
        logger.info("OnRtnErrorConditionalOrder: "+pErrorConditionalOrder);
    }

    @Override
    public void OnRtnExecOrder(CThostFtdcExecOrderField pExecOrder) {
        logger.info("OnRtnExecOrder: "+pExecOrder);
    }

    @Override
    public void OnErrRtnExecOrderInsert(CThostFtdcInputExecOrderField pInputExecOrder, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnExecOrderInsert: "+pInputExecOrder);
    }

    @Override
    public void OnErrRtnExecOrderAction(CThostFtdcExecOrderActionField pExecOrderAction, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnExecOrderAction: "+pExecOrderAction);
    }

    @Override
    public void OnErrRtnForQuoteInsert(CThostFtdcInputForQuoteField pInputForQuote, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnForQuoteInsert: "+pInputForQuote);
    }

    @Override
    public void OnRtnQuote(CThostFtdcQuoteField pQuote) {
        logger.info("OnRtnQuote: "+pQuote);
    }

    @Override
    public void OnErrRtnQuoteInsert(CThostFtdcInputQuoteField pInputQuote, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnQuoteInsert: "+pInputQuote);
    }

    @Override
    public void OnErrRtnQuoteAction(CThostFtdcQuoteActionField pQuoteAction, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnQuoteAction: "+pQuoteAction+" "+pRspInfo);
    }

    @Override
    public void OnRtnForQuoteRsp(CThostFtdcForQuoteRspField pForQuoteRsp) {
        logger.info("OnRtnForQuoteRsp: "+pForQuoteRsp);
    }

    @Override
    public void OnRtnCFMMCTradingAccountToken(CThostFtdcCFMMCTradingAccountTokenField pCFMMCTradingAccountToken) {
        logger.info("OnRtnCFMMCTradingAccountToken: "+pCFMMCTradingAccountToken);
    }

    @Override
    public void OnErrRtnBatchOrderAction(CThostFtdcBatchOrderActionField pBatchOrderAction, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnBatchOrderAction: "+pBatchOrderAction+" "+pRspInfo);
    }

    @Override
    public void OnRtnOptionSelfClose(CThostFtdcOptionSelfCloseField pOptionSelfClose) {
        logger.info("OnRtnOptionSelfClose: "+pOptionSelfClose);
    }

    @Override
    public void OnErrRtnOptionSelfCloseInsert(CThostFtdcInputOptionSelfCloseField pInputOptionSelfClose, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnOptionSelfCloseInsert: "+pInputOptionSelfClose+" "+pRspInfo);
    }

    @Override
    public void OnErrRtnOptionSelfCloseAction(CThostFtdcOptionSelfCloseActionField pOptionSelfCloseAction, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnOptionSelfCloseAction: "+pOptionSelfCloseAction+" "+pRspInfo);
    }

    @Override
    public void OnRtnCombAction(CThostFtdcCombActionField pCombAction) {
        logger.info("OnRtnCombAction: "+pCombAction);
    }

    @Override
    public void OnErrRtnCombActionInsert(CThostFtdcInputCombActionField pInputCombAction, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnCombActionInsert: "+pInputCombAction+" "+pRspInfo);
    }

    @Override
    public void OnRspQryContractBank(CThostFtdcContractBankField pContractBank, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryContractBank: "+pContractBank+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryParkedOrder(CThostFtdcParkedOrderField pParkedOrder, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryParkedOrder: "+pParkedOrder+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryParkedOrderAction(CThostFtdcParkedOrderActionField pParkedOrderAction, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryParkedOrderAction: "+pParkedOrderAction+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryTradingNotice(CThostFtdcTradingNoticeField pTradingNotice, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryTradingNotice: "+pTradingNotice+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryBrokerTradingParams(CThostFtdcBrokerTradingParamsField pBrokerTradingParams, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryBrokerTradingParams: "+pBrokerTradingParams+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQryBrokerTradingAlgos(CThostFtdcBrokerTradingAlgosField pBrokerTradingAlgos, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQryBrokerTradingAlgos: "+pBrokerTradingAlgos+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQueryCFMMCTradingAccountToken(CThostFtdcQueryCFMMCTradingAccountTokenField pQueryCFMMCTradingAccountToken, CThostFtdcRspInfoField pRspInfo, int nRequestID,
    boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQueryCFMMCTradingAccountToken: "+pQueryCFMMCTradingAccountToken+" "+pRspInfo);
        }
    }

    @Override
    public void OnRtnFromBankToFutureByBank(CThostFtdcRspTransferField pRspTransfer) {
        logger.info("OnRtnFromBankToFutureByBank: "+pRspTransfer);
    }

    @Override
    public void OnRtnFromFutureToBankByBank(CThostFtdcRspTransferField pRspTransfer) {
        logger.info("OnRtnFromFutureToBankByBank: "+pRspTransfer);
    }

    @Override
    public void OnRtnRepealFromBankToFutureByBank(CThostFtdcRspRepealField pRspRepeal) {
        logger.info("OnRtnRepealFromBankToFutureByBank: "+pRspRepeal);
    }

    @Override
    public void OnRtnRepealFromFutureToBankByBank(CThostFtdcRspRepealField pRspRepeal) {
        logger.info("OnRtnRepealFromFutureToBankByBank: "+pRspRepeal);
    }

    @Override
    public void OnRtnFromBankToFutureByFuture(CThostFtdcRspTransferField pRspTransfer) {
        logger.info("OnRtnFromBankToFutureByFuture: "+pRspTransfer);
    }

    @Override
    public void OnRtnFromFutureToBankByFuture(CThostFtdcRspTransferField pRspTransfer) {
        logger.info("OnRtnFromFutureToBankByFuture: "+pRspTransfer);
    }

    @Override
    public void OnRtnRepealFromBankToFutureByFutureManual(CThostFtdcRspRepealField pRspRepeal) {
        logger.info("OnRtnRepealFromBankToFutureByFutureManual: "+pRspRepeal);
    }

    @Override
    public void OnRtnRepealFromFutureToBankByFutureManual(CThostFtdcRspRepealField pRspRepeal) {
        logger.info("OnRtnRepealFromFutureToBankByFutureManual: "+pRspRepeal);
    }

    @Override
    public void OnRtnQueryBankBalanceByFuture(CThostFtdcNotifyQueryAccountField pNotifyQueryAccount) {
        logger.info("OnRtnQueryBankBalanceByFuture: "+pNotifyQueryAccount);
    }

    @Override
    public void OnErrRtnBankToFutureByFuture(CThostFtdcReqTransferField pReqTransfer, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnBankToFutureByFuture: "+pReqTransfer+" "+pRspInfo);
    }

    @Override
    public void OnErrRtnFutureToBankByFuture(CThostFtdcReqTransferField pReqTransfer, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnFutureToBankByFuture: "+pReqTransfer+" "+pRspInfo);
    }

    @Override
    public void OnErrRtnRepealBankToFutureByFutureManual(CThostFtdcReqRepealField pReqRepeal, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnRepealBankToFutureByFutureManual: "+pReqRepeal+" "+pRspInfo);
    }

    @Override
    public void OnErrRtnRepealFutureToBankByFutureManual(CThostFtdcReqRepealField pReqRepeal, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnRepealFutureToBankByFutureManual: "+pReqRepeal+" "+pRspInfo);
    }

    @Override
    public void OnErrRtnQueryBankBalanceByFuture(CThostFtdcReqQueryAccountField pReqQueryAccount, CThostFtdcRspInfoField pRspInfo) {
        logger.info("OnErrRtnQueryBankBalanceByFuture: "+pReqQueryAccount+" "+pRspInfo);
    }

    @Override
    public void OnRtnRepealFromBankToFutureByFuture(CThostFtdcRspRepealField pRspRepeal) {
        logger.info("OnRtnRepealFromBankToFutureByFuture: "+pRspRepeal);
    }

    @Override
    public void OnRtnRepealFromFutureToBankByFuture(CThostFtdcRspRepealField pRspRepeal) {
        logger.info("OnRtnRepealFromFutureToBankByFuture: "+pRspRepeal);
    }

    @Override
    public void OnRspFromBankToFutureByFuture(CThostFtdcReqTransferField pReqTransfer, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspFromBankToFutureByFuture: "+pReqTransfer+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspFromFutureToBankByFuture(CThostFtdcReqTransferField pReqTransfer, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspFromFutureToBankByFuture: "+pReqTransfer+" "+pRspInfo);
        }
    }

    @Override
    public void OnRspQueryBankAccountMoneyByFuture(CThostFtdcReqQueryAccountField pReqQueryAccount, CThostFtdcRspInfoField pRspInfo, int nRequestID, boolean bIsLast) {
        if ( logger.isDebugEnabled() ) {
            logger.debug("OnRspQueryBankAccountMoneyByFuture: "+pReqQueryAccount+" "+pRspInfo);
        }
    }

    @Override
    public void OnRtnOpenAccountByBank(CThostFtdcOpenAccountField pOpenAccount) {
        logger.info("OnRtnOpenAccountByBank: "+pOpenAccount);
    }

    @Override
    public void OnRtnCancelAccountByBank(CThostFtdcCancelAccountField pCancelAccount) {
        logger.info("OnRtnCancelAccountByBank: "+pCancelAccount);
    }

    @Override
    public void OnRtnChangeAccountByBank(CThostFtdcChangeAccountField pChangeAccount) {
        logger.info("OnRtnChangeAccountByBank: "+pChangeAccount);
    }

}
