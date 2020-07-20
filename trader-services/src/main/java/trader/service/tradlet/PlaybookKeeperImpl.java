package trader.service.tradlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import trader.common.beans.BeansContainer;
import trader.common.exception.AppException;
import trader.common.exchangeable.Exchangeable;
import trader.common.util.DateUtil;
import trader.common.util.JsonEnabled;
import trader.common.util.JsonUtil;
import trader.common.util.PriceUtil;
import trader.common.util.StringUtil;
import trader.common.util.UUIDUtil;
import trader.service.ServiceErrorConstants;
import trader.service.md.MarketData;
import trader.service.repository.BOEntity;
import trader.service.repository.BOEntityIterator;
import trader.service.repository.BORepository;
import trader.service.repository.BORepositoryConstants.BOEntityType;
import trader.service.trade.MarketTimeService;
import trader.service.trade.Order;
import trader.service.trade.TradeConstants;
import trader.service.trade.TradeService;
import trader.service.trade.Transaction;

/**
 * 管理某个交易分组的报单和成交计划
 */
public class PlaybookKeeperImpl implements PlaybookKeeper, TradeConstants, TradletConstants, ServiceErrorConstants, JsonEnabled {
    private static final Logger logger = LoggerFactory.getLogger(PlaybookKeeperImpl.class);

    private TradletGroupImpl group;
    private Map<String, Map<String, String>> templates = new HashMap<>();
    private MarketTimeService mtService;
    private List<Order> allOrders = new ArrayList<>();
    private LinkedList<Order> pendingOrders = new LinkedList<>();
    private LinkedHashMap<String, PlaybookImpl> allPlaybooks = new LinkedHashMap<>();
    private LinkedList<PlaybookImpl> activePlaybooks = new LinkedList<>();

    public PlaybookKeeperImpl(TradletGroupImpl group) {
        this.group = group;
        BeansContainer beansContainer = group.getBeansContainer();
        mtService = beansContainer.getBean(MarketTimeService.class);
        TradeService tradeService = beansContainer.getBean(TradeService.class);
        if ( tradeService.getType()==TradeServiceType.RealTime ) {
            restorePlaybooks(beansContainer);
        }
    }

    public void update(String configText) {
        templates = new HashMap<>();
        if ( !StringUtil.isEmpty(configText) ) {
            Properties props = StringUtil.text2properties(configText);
            for(Object key:props.keySet()) {
                String templateId = key.toString();
                Map<String, String> templateParams = new HashMap<>();
                for(String[] kv : StringUtil.splitKVs(props.getProperty(templateId))) {
                    if ( kv.length>=2 ) {
                        templateParams.put(kv[0], kv[1]);
                    }
                }
                templates.put(templateId, templateParams);
            }
        }
    }

    @Override
    public List<Order> getAllOrders() {
        return Collections.unmodifiableList(allOrders);
    }

    @Override
    public List<Order> getPendingOrders() {
        return Collections.unmodifiableList(pendingOrders);
    }

    @Override
    public Order getLastOrder() {
        if ( allOrders.isEmpty() ) {
            return null;
        }
        return allOrders.get(allOrders.size()-1);
    }

    @Override
    public Order getLastPendingOrder() {
        if ( pendingOrders.isEmpty() ) {
            return null;
        }
        return pendingOrders.getLast();
    }

    @Override
    public void cancelAllPendingOrders() {
        for(Order order:pendingOrders) {
            if ( order.getStateTuple().getState().isRevocable() ) {
                try {
                    group.getAccount().cancelOrder(order.getId());
                } catch (AppException e) {
                    logger.error("Tradlet group "+group.getId()+" cancel order "+order.getId()+" failed "+e.toString(), e);
                }
            }
        }
    }

    @Override
    public Collection<Playbook> getAllPlaybooks() {
        return (Collection)Collections.unmodifiableCollection(allPlaybooks.values());
    }

    @Override
    public List<Playbook> getActivePlaybooks(Exchangeable instrument) {
        List<Playbook> result = new ArrayList<>();
        if ( null==instrument) {
            result = (List)activePlaybooks;
        }else{
            for(Playbook pb:activePlaybooks) {
                if ( instrument.equals(pb.getInstrument())) {
                    result.add(pb);
                }
            }
        }
        return result;
    }

    @Override
    public Playbook getPlaybook(String playbookId) {
        return allPlaybooks.get(playbookId);
    }

    @Override
    public Playbook createPlaybook(Tradlet tradlet, PlaybookBuilder builder) throws AppException
    {
        if ( group.getState()!=TradletGroupState.Enabled ) {
            throw new AppException(ERR_TRADLET_TRADLETGROUP_NOT_ENABLED, "Tradlet group "+group.getId()+" is not enabled");
        }
        Map<String, String> templateParams = null;
        //加载template对应的参数:
        if ( !StringUtil.isEmpty(builder.getTemplateId())) {
            templateParams = templates.get(builder.getTemplateId());
        }
        String playbookId = BOEntity.ID_PREFIX_PLAYBOOK+UUIDUtil.genUUID58();
        if ( builder.getInstrument()==null ) {
            builder.setInstrument(group.getInstruments().get(0));
        }
        PlaybookImpl playbook = new PlaybookImpl(group, playbookId, builder);
        //填充playbook template params
        if ( templateParams!=null ) {
            for(String param:templateParams.keySet()) {
                String paramv = templateParams.get(param);
                if ( !StringUtil.isEmpty(paramv)) {
                    playbook.setAttr(param, paramv);
                }
            }
        }
        if ( tradlet!=null ) {
            playbook.setAttr(PBATTR_TRADLET_ID.name(), group.getTradletId(tradlet));
        }
        allPlaybooks.put(playbookId, playbook);
        activePlaybooks.add(playbook);
        if ( logger.isInfoEnabled()) {
            logger.info("Tradlet group "+group.getId()+" playbook "+playbookId+" is created with attrs: "+builder.getAttrs());
        }
        group.onPlaybookStateChanged(playbook, null);
        //kvStore.aput(id, toJson().toString());
        return playbook;
    }

    @Override
    public boolean closePlaybook(Playbook playbook0, PlaybookCloseReq closeReq) {
        boolean result = false;
        if ( playbook0!=null ) {
            PlaybookImpl playbook = (PlaybookImpl)playbook0;
            PlaybookStateTuple pbStateTuple = playbook.getStateTuple();
            PlaybookState pbState = pbStateTuple.getState();
            switch(pbState) {
            case Opening: //开仓过程中, 取消报单
                result = playbook.cancelOpeningOrder();
                break;
            case Opened: //已开仓, 平仓
                result = playbook.closeOpenedOrder(closeReq.getActionId());
                break;
            default:
                result = false;
                break;
            }
            if ( result ) {
                if ( closeReq.getTimeout()>0 ) {
                    playbook.setAttr(Playbook.PBATTR_CLOSE_TIMEOUT.name(), ""+closeReq.getTimeout());
                }
                if ( logger.isInfoEnabled()) {
                    logger.info("Tradlet group "+group.getId()+" close playbook "+playbook.getId()+" action id "+closeReq.getActionId()+" at "+DateUtil.date2str(mtService.getMarketTime()));
                }
            }
        }
        //kvStore.aput(id, toJson().toString());
        return result;
    }

    public void updateOnTxn(Order order, Transaction txn) {
        PlaybookImpl playbook = null;
        if ( order!=null ) {
            String playbookId = order.getAttr(Order.ODRATTR_PLAYBOOK_ID);
            playbook = allPlaybooks.get(playbookId);
        }
        if ( playbook!=null ) {
            playbook.updateOnTxn(order, txn);
        }
        //kvStore.aput(id, toJson().toString());
    }

    /**
     * 更新订单状态
     */
    public void updateOnOrder(Order order) {
        String playbookId = order.getAttr(Order.ODRATTR_PLAYBOOK_ID);
        PlaybookImpl playbook = allPlaybooks.get(playbookId);
        if ( playbook==null ) {
            return;
        }
        if ( order.getStateTuple().getState().isDone() ) {
            pendingOrders.remove(order);
        }
        PlaybookStateTuple oldStateTuple = playbook.updateStateOnOrder(order);
        if ( oldStateTuple!=null ) {
            playbookChangeStateTuple(playbook, oldStateTuple,"Order "+order.getRef()+" "+order.getInstrument()+" D:"+order.getDirection()+" P:"+PriceUtil.long2str(order.getLimitPrice())+" V:"+order.getVolume(OdrVolume.ReqVolume)+" F:"+order.getOffsetFlags()+" at "+DateUtil.date2str(mtService.getMarketTime()));
        }
        //kvStore.aput(id, toJson().toString());
    }

    public void updateOnTick(MarketData tick) {
        for(PlaybookImpl playbook:activePlaybooks) {
            PlaybookStateTuple oldStateTuple = playbook.updateStateOnTick(tick);
            if ( oldStateTuple!=null ) {
                playbookChangeStateTuple(playbook, oldStateTuple, "noop");
            }
        }
    }

    /**
     * 判断超时Playbook
     */
    public void onNoopSecond() {
        for(PlaybookImpl playbook:activePlaybooks) {
            PlaybookStateTuple oldStateTuple = playbook.updateStateOnNoop();
            if ( oldStateTuple!=null ) {
                playbookChangeStateTuple(playbook, oldStateTuple, "noop");
            }
        }
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.add("allOrders", JsonUtil.object2json(allOrders));
        json.add("pendingOrders", JsonUtil.object2json(pendingOrders));
        json.addProperty("activePlaybookCount", activePlaybooks.size());
        json.add("allPlaybooks", JsonUtil.object2json(allPlaybooks.values()));
        return json;
    }

    private void playbookChangeStateTuple(PlaybookImpl playbook, PlaybookStateTuple oldStateTuple, String time) {
        if ( oldStateTuple!=null ) {
            PlaybookState newState = playbook.getStateTuple().getState();
            int lastOrderCount = playbook.getOrders().size();
            logger.info("Tradlet group "+group.getId()+" playbook "+playbook.getId()+" state is changed from "+oldStateTuple.getState()+" to "+newState+" on "+time);
            List<Order> playbookOrders = playbook.getOrders();
            //检查是否有新的报单
            if ( lastOrderCount!=playbookOrders.size() ) {
                Order newOrder = playbookOrders.get(lastOrderCount);
                addOrder(newOrder);
            }
            //检查Playbook状态
            if ( newState.isDone() ) {
                activePlaybooks.remove(playbook);
            }
            group.onPlaybookStateChanged(playbook, oldStateTuple);
        }
    }

    private void addOrder(Order order) {
        allOrders.add(order);
        pendingOrders.add(order);
    }

    /**
     * 从数据库加载本交易日的Playbook
     */
    private void restorePlaybooks(BeansContainer beansContainer) {
        BORepository repository = beansContainer.getBean(BORepository.class);
        try{
            String groupId = group.getId();
            String queryExpr = "tradingDay='"+DateUtil.date2str(mtService.getTradingDay())+"' AND groupId='"+groupId+"'";
            BOEntityIterator entityIter = repository.search(BOEntityType.Playbook, queryExpr);
            while(entityIter.hasNext()) {
                entityIter.next();
                PlaybookImpl pb = (PlaybookImpl)entityIter.getEntity();
                allPlaybooks.put(pb.getId(), pb);
                if ( !pb.getStateTuple().getState().isDone() ) {
                    activePlaybooks.add(pb);
                }
            }
        }catch(Throwable t) {
            logger.error("Tradlet group "+group.getId()+" restore playbooks failed", t);
        }
    }

}
