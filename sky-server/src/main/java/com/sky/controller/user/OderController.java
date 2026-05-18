package com.sky.controller.user;

import com.sky.controller.notify.PayNotifyController;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController("userOrderController")
@RequestMapping("/user/order")
@Slf4j
@Api(tags = "C端-订单接口")
public class OderController {

    @Autowired
    private OrderService orderService;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @PostMapping("/submit")
    @ApiOperation("用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO) throws IOException {
        log.info("用户下单：{}", ordersSubmitDTO);
        OrderSubmitVO orderSubmitVO = orderService.submit(ordersSubmitDTO);
        return Result.success(orderSubmitVO);
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    @ApiOperation("订单支付")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("订单支付：{}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderService.payment(ordersPaymentDTO);
        log.info("生成预支付交易单：{}", orderPaymentVO);
        orderService.paySuccess(ordersPaymentDTO.getOrderNumber());
        return Result.success(orderPaymentVO);
    }

    /**
     * 历史订单查询
     *
     * @param ordersPageQueryDTO
     * @return
     */
    @GetMapping("/historyOrders")
    @ApiOperation("查询历史订单")
    public Result<PageResult> page(OrdersPageQueryDTO ordersPageQueryDTO) {
        log.info("查询历史订单：{}", ordersPageQueryDTO);
        PageResult pageResult = orderService.page(ordersPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    @GetMapping("/orderDetail/{id}")
    @ApiOperation("查询订单详情")
    public Result<OrderVO> getOrderDetail(@PathVariable Long id) {
        log.info("查询订单详情，订单id为：{}", id);
        OrderVO orderVO = orderService.getOrderDetail(id);
        return Result.success(orderVO);
    }

    /**
     * 取消订单
     *
     * @param id
     * @return
     */
    @PutMapping("/cancel/{id}")
    @ApiOperation("取消订单")
    public Result cancel(@PathVariable Long id, @RequestBody OrdersCancelDTO ordersCancelDTO) {
        log.info("取消订单，订单id为：{}", id);
        ordersCancelDTO.setId(id);
        orderService.cancel(ordersCancelDTO);
        return Result.success();
    }

    /**
     * 再来一单
     *
     * @param id
     * @return
     */
    @PostMapping("/repetition/{id}")
    @ApiOperation("再来一单")
    public Result repetition(@PathVariable Long id) {
        log.info("再来一单，订单id为：{}", id);
        orderService.repetition(id);
        return Result.success();
    }
}
