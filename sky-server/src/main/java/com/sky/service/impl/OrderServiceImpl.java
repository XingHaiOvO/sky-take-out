package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.properties.BaiDuProperties;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailsMapper;

    @Autowired
    private OrderDetailMapper oderDetailMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private OrderService orderService;

    @Autowired
    private BaiDuProperties baiDuProperties;

    private static final String ApiURL1 = "https://api.map.baidu.com/geocoding/v3";

    private static final String ApiURL2 = "https://api.map.baidu.com/direction/v2/driving";

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO) {

        // 处理业务异常（地址簿为空、购物车数据为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            // 抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        checkOutOfLocation(addressBook);

        // 查询购物车数据
        Long userId = BaseContext.getCurrentId();
        List<ShoppingCart> list = shoppingCartMapper.list(ShoppingCart.builder().userId(userId).build());
        if (list == null || list.size() == 0) {
            // 抛出业务异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 向订单表插入一条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        String address = addressBook.getProvinceName() + addressBook.getCityName() + addressBook.getDistrictName() + addressBook.getDetail();
        orders.setAddress(address);
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);

        orderMapper.insert(orders);

        // 向订单明细表插入n条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        list.forEach(shoppingCart -> {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(shoppingCart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        });
        oderDetailMapper.insertBatch(orderDetailList);

        // 清空购物车数据
        shoppingCartMapper.deleteByUserId(userId);

        // 封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();

        return orderSubmitVO;
    }

    /**
     * 检查用户是否超出配送范围
     */
    private void checkOutOfLocation(AddressBook addressBook) {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("address", baiDuProperties.getAddress());
        queryParams.put("output", "json");
        queryParams.put("ak", baiDuProperties.getAk());

        // 获取商家的经纬度
        String response = HttpClientUtil.doGet(ApiURL1, queryParams);

        JSONObject jsonObject = JSONObject.parseObject(response);
        Integer status = jsonObject.getInteger("status");
        if (status != 0) {
            log.info("获取商家经纬度失败");
            throw new OrderBusinessException(MessageConstant.LOCATION_PARSE_ERROR);
        }

        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        Double latitude = location.getDouble("lat");
        Double longitude = location.getDouble("lng");
        String shopLocation = latitude + "," + longitude;

        // 获取用户经纬度
        queryParams.put("address", addressBook.getDetail());
        response = HttpClientUtil.doGet(ApiURL1, queryParams);
        jsonObject = JSONObject.parseObject(response);
        status = jsonObject.getInteger("status");
        if (status != 0) {
            log.info("获取用户经纬度失败");
            throw new OrderBusinessException(MessageConstant.LOCATION_PARSE_ERROR);
        }
        location = jsonObject.getJSONObject("result").getJSONObject("location");
        latitude = location.getDouble("lat");
        longitude = location.getDouble("lng");
        String userLocation = latitude + "," + longitude;

        // 获取道路规划
        queryParams.clear();
        queryParams.put("origin", shopLocation);
        queryParams.put("destination", userLocation);
        queryParams.put("ak", baiDuProperties.getAk());
        response = HttpClientUtil.doGet(ApiURL2, queryParams);
        jsonObject = JSONObject.parseObject(response);
        status = jsonObject.getInteger("status");
        if (status != 0) {
            log.info("获取道路规划失败");
            throw new OrderBusinessException(MessageConstant.ROUTER_PLANNING_ERROR);
        }

        // 获取路线距离
        Object route = jsonObject.getJSONObject("result").getJSONArray("routes").get(0);
        Integer distance = JSONObject.parseObject(route.toString()).getInteger("distance");
        if (distance > 5000) {
            log.info("超出配送范围");
            throw new OrderBusinessException(MessageConstant.OUT_OF_DELIVERY_RANGE);
        }
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
        /*JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );
        */

        JSONObject jsonObject = new JSONObject();
        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
    }

    /**
     * 查询历史订单
     *
     * @param ordersPageQuery
     * @return
     */
    public PageResult page(OrdersPageQueryDTO ordersPageQuery) {
        ordersPageQuery.setUserId(BaseContext.getCurrentId());
        PageHelper.startPage(ordersPageQuery.getPage(), ordersPageQuery.getPageSize());
        Page<Orders> page = orderMapper.page(ordersPageQuery);
        List<OrderVO> list = new ArrayList<>();
        page.getResult().forEach(order -> {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(order, orderVO);
            orderVO.setOrderDetailList(orderDetailsMapper.getByOrderId(order.getId()));
            list.add(orderVO);
        });

        return new PageResult(page.getTotal(), list);
    }

    /**
     * 订单详情
     *
     * @param id
     * @return
     */
    public OrderVO getOrderDetail(Long id) {
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orderMapper.getById(id), orderVO);
        orderVO.setOrderDetailList(orderDetailsMapper.getByOrderId(id));
        return orderVO;
    }

    /**
     * 取消订单
     *
     * @param ordersCancelDTO
     */
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        Orders orders = orderMapper.getById(ordersCancelDTO.getId());
        if (orders == null || orders.getStatus() == 5) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        if (orders.getPayStatus().equals(Orders.PAID)) {
            log.info("退款");
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 再来一单
     *
     * @param id
     */
    public void repetition(Long id) {
        Long userId = BaseContext.getCurrentId();

        List<OrderDetail> orderDetailList = orderDetailsMapper.getByOrderId(id);

        List<ShoppingCart> list = orderDetailList.stream().map(orderDetail -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart);
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());
        shoppingCartMapper.insertBatch(list);
    }

    /**
     * 条件搜索订单
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.page(ordersPageQueryDTO);

        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 统计订单数据
     *
     * @return
     */
    public OrderStatisticsVO statistics() {
        Integer toBeConfirmed = orderMapper.countByStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countByStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countByStatus(Orders.DELIVERY_IN_PROGRESS);
        OrderStatisticsVO orderStatisticsVO = OrderStatisticsVO
                .builder()
                .toBeConfirmed(toBeConfirmed)
                .confirmed(confirmed)
                .deliveryInProgress(deliveryInProgress)
                .build();
        return orderStatisticsVO;
    }

    /**
     * 订单确认
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = orderMapper.getById(ordersConfirmDTO.getId());
        if (orders == null || !orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orders.setStatus(Orders.CONFIRMED);
        orderMapper.update(orders);
    }

    /**
     * 订单拒绝
     *
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        Orders orders = orderMapper.getById(ordersRejectionDTO.getId());
        if (orders == null || !orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        if (orders.getPayStatus().equals(Orders.PAID)) {
            log.info("退款");
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 订单派送
     *
     * @param id
     */
    public void delivery(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
    }

    /**
     * 订单完成
     *
     * @param id
     */
    public void complete(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orders.setStatus(Orders.COMPLETED);
        orderMapper.update(orders);
    }
}
