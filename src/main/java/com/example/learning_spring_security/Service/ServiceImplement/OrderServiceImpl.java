package com.example.learning_spring_security.Service.ServiceImplement;

import com.example.learning_spring_security.Constant.CancelReason;
import com.example.learning_spring_security.Constant.CancelStatus;
import com.example.learning_spring_security.Constant.OrderStatus;
import com.example.learning_spring_security.Enumeration.PaymentMethod;
import com.example.learning_spring_security.Enumeration.TransactionStatus;
import com.example.learning_spring_security.Exception.ExceptionService.BadRequestException;
import com.example.learning_spring_security.Exception.ExceptionService.ResourceNotFoundException;
import com.example.learning_spring_security.Model.*;
import com.example.learning_spring_security.Repository.*;
import com.example.learning_spring_security.Service.ServiceStructure.InventoryService;
import com.example.learning_spring_security.Service.ServiceStructure.OrderService;
import com.example.learning_spring_security.Service.ServiceStructure.PaymentTransactionService;
import com.example.learning_spring_security.dto.Request.PaymentTransactionRequest;

import com.example.learning_spring_security.ServiceMapper.OrderItemMapper;
import com.example.learning_spring_security.ServiceMapper.OrderMapper;
import com.example.learning_spring_security.ServiceMapper.PaymentMapper;
import com.example.learning_spring_security.dto.Request.GetOrderRequest;
import com.example.learning_spring_security.dto.Request.OrderRequest;
import com.example.learning_spring_security.dto.Response.*;

import com.example.learning_spring_security.Bakong.service.impl.dto.BakongRequest;
import com.example.learning_spring_security.Bakong.service.impl.dto.BakongResponse;
import com.example.learning_spring_security.Bakong.service.impl.service.BakongService;
import com.example.learning_spring_security.Bakong.service.impl.dto.CheckTransactionRequest;
import com.example.learning_spring_security.utils.PaymentCodeGenerator;
import kh.gov.nbc.bakong_khqr.model.KHQRData;
import kh.gov.nbc.bakong_khqr.model.KHQRDeepLinkData;
import kh.gov.nbc.bakong_khqr.model.KHQRResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.learning_spring_security.Service.FirebaseNotificationService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final PaymentCodeGenerator paymentCodeGenerator;
    private final OrderCancelationRepository orderCancelationRepository;

    private final BakongService bakongService;
    private final PaymentTransactionService paymentTransactionService;
    private final EmailNotificationService emailNotificationService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final FirebaseNotificationService firebaseNotificationService;

    @Value("${app.exchange-rate.usd-to-khr}")
    private double usdToKhrRate;

    @Override
    @Transactional
    public ResponseErrorTemplate createOrderFromCart(Long userId, OrderRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user"));

        if (cart.getCartItems().isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }
        Address shippingAddress = addressRepository.findById(request.getAddressId())
                .orElseThrow(
                        () -> new ResourceNotFoundException("Address not found with id: " + request.getAddressId()));

        if (!addressRepository.isUserHasAddress(userId, request.getAddressId())) {
            throw new BadRequestException("Invalid shipping address");
        }

        OrderDetail order = OrderDetail.builder()
                .user(user)
                .orderNumber(generateOrderNumber())
                .orderDate(LocalDateTime.now())
                .status(OrderStatus.PENDING)
                .totalAmount(cart.getTotalPrice())
                .shippingAddress(shippingAddress)
                .build();

        List<OrderItem> orderItems = cart.getCartItems().stream()
                .map(cartItem -> {
                    inventoryService.reduceStock(cartItem.getProductSku().getId(), cartItem.getQuantity());
                    return OrderItemMapper.toEntity(
                            order,
                            cartItem.getProductSku(),
                            cartItem.getQuantity());
                })
                .collect(Collectors.toList());
        order.setOrderItems(orderItems);

        Payment payment = Payment.builder()
                .orderDetail(order)
                .paymentMethod(request.getPaymentMethod())
                .amount(order.getTotalAmount())
                .currency(resolveCurrency(request.getCurrency()))
                .status(OrderStatus.PENDING)
                .paymentDate(LocalDateTime.now())
                .build();

        order.setPayment(payment);
        OrderDetail savedOrder = orderRepository.save(order);

        cart.getCartItems().clear();
        cart.setTotalPrice(BigDecimal.ZERO);
        cart.setTotalItems(0);
        cartRepository.save(cart);

        try {
            emailNotificationService.sendOrderConfirmationEmail(
                    user.getEmail(),
                    savedOrder.getOrderNumber(),
                    savedOrder.getTotalAmount().doubleValue());
        } catch (Exception e) {
            log.warn("Failed to send order confirmation email for order {}: {}", savedOrder.getOrderNumber(),
                    e.getMessage());
        }

        // =========================

        // ORDER CREATED FCM

        // =========================

        try {

            log.info(

                    "🔥 START FCM: order={}, customerId={}, method={}",

                    savedOrder.getOrderNumber(),

                    user.getId(),

                    request.getPaymentMethod()

            );

            firebaseNotificationService.sendToUser(

                    user.getId(),

                    "Order Placed 🎉",

                    "Your order " + savedOrder.getOrderNumber()

                            + " has been successfully placed.",

                    Map.of(

                            "type", "ORDER_NEW",

                            "orderId", savedOrder.getId().toString(),

                            "orderNumber", savedOrder.getOrderNumber(),

                            "status", savedOrder.getStatus()

                    )

            );

            log.info(

                    "✅ END FCM: order={}, customerId={}",

                    savedOrder.getOrderNumber(),

                    user.getId()

            );

        } catch (Exception e) {

            // FCM error មិនឱ្យធ្វើឱ្យ Create Order fail

            log.warn(

                    "Failed to send order created FCM. order={}, error={}",

                    savedOrder.getOrderNumber(),

                    e.getMessage()

            );

        }

        ResponseErrorTemplate orderResponse = orderMapper.toResponse(savedOrder);

        if (isKhqrPaymentMethod(request.getPaymentMethod())) {
            return pushToBakong(orderResponse);
        }

        return orderResponse;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getOrders(GetOrderRequest request) {
        log.info("getOrders: criteriaType={}, criteriaValue={}, page={}, size={}",
                request.getCriteriaType(), request.getCriteriaValue(), request.getPage(), request.getSize());

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                request.getPage() - 1,
                request.getSize(),
                org.springframework.data.domain.Sort.by("orderDate").descending());

        Integer type = request.getCriteriaType();
        String value = request.getCriteriaValue();

        org.springframework.data.domain.Page<OrderDetail> page;
        String successMsg;

        if (type == null || type == 0 || value == null || value.isBlank()) {
            page = orderRepository.findAll(pageable);
            successMsg = "Retrieved all orders";

        } else if (type == 1) {
            // by userId
            page = orderRepository.findByUserId(Long.parseLong(value), pageable);
            successMsg = "Retrieved orders by user";

        } else if (type == 2) {
            // by status
            page = orderRepository.findByStatus(value, pageable);
            successMsg = "Retrieved orders by status";

        } else if (type == 3) {
            // by userId + status, criteriaValue format: "userId:status"
            String[] parts = value.split(":");
            if (parts.length != 2) {
                throw new BadRequestException("criteriaValue for type 3 must be 'userId:status'");
            }
            page = orderRepository.findOrderDetailHistory(
                    Long.parseLong(parts[0].trim()),
                    parts[1].trim(),
                    null, null,
                    pageable);
            successMsg = "Retrieved orders by user and status";

        } else if (type == 4) {
            // by userId + date range, criteriaValue format: "userId:startDate:endDate"
            String[] parts = value.split(":");
            if (parts.length != 3) {
                throw new BadRequestException(
                        "criteriaValue for type 4 must be 'userId:startDate:endDate' (yyyy-MM-ddTHH:mm:ss)");
            }
            page = orderRepository.findOrderDetailHistory(
                    Long.parseLong(parts[0].trim()),
                    null,
                    java.time.LocalDateTime.parse(parts[1].trim()),
                    java.time.LocalDateTime.parse(parts[2].trim()),
                    pageable);
            successMsg = "Retrieved orders by user and date range";

        } else {
            page = orderRepository.findAll(pageable);
            successMsg = "Retrieved all orders";
        }

        java.util.List<OrderResponse> payload = page.getContent()
                .stream()
                .map(o -> (OrderResponse) orderMapper.toResponse(o).object())
                .toList();

        OrderPageResponse pageResponse = OrderPageResponse.builder()
                .payload(payload)
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .currentPage(page.getNumber() + 1)
                .pageSize(page.getSize())
                .build();

        String message = page.isEmpty() ? "No orders found" : successMsg;
        return ResponseErrorTemplate.success(message, pageResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getOrderById(Long id) {
        OrderDetail order = orderRepository.findByIdWithFullDetail(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));
        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getOrderByNumber(String orderNumber) {
        OrderDetail order = orderRepository.findByOrderNumberWithFullDetail(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with number: " + orderNumber));
        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getUserOrders(Long userId, Pageable pageable) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return orderRepository.findByUserId(userId, pageable)
                .map(orderMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(orderMapper::toResponse);
    }

    @Override
    public ResponseErrorTemplate updateOrderStatus(Long id, String status) {
        OrderDetail order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));

        order.setStatus(status);

        if (OrderStatus.DELIVERED.equals(status) && order.getPayment() != null) {
            order.getPayment().setStatus(OrderStatus.COMPLETED);
        } else if (OrderStatus.CANCELLED.equals(status) && order.getPayment() != null) {
            order.getPayment().setStatus(OrderStatus.REFUNDED);
            order.getOrderItems().forEach(
                    item -> inventoryRepository.increaseStock(item.getProductSku().getId(), item.getQuantity()));
        }

        OrderDetail updatedOrder = orderRepository.save(order);

        try {
            String customerEmail = updatedOrder.getUser().getEmail();
            if (OrderStatus.SHIPPED.equals(status)) {
                emailNotificationService.sendOrderShippedEmail(
                        customerEmail,
                        updatedOrder.getOrderNumber(),
                        updatedOrder.getOrderNumber());
            } else if (OrderStatus.CANCELLED.equals(status)) {
                emailNotificationService.sendOrderCancellationEmail(
                        customerEmail,
                        updatedOrder.getOrderNumber(),
                        "Order cancelled by admin");
            }
        } catch (Exception e) {
            log.warn("Failed to send status update email for order {}: {}", updatedOrder.getOrderNumber(),
                    e.getMessage());
        }

        log.info(
                "🔥 START FCM: order={}, customerId={}, status={}",
                updatedOrder.getOrderNumber(),
                updatedOrder.getUser().getId(),
                status);

        try {
            Long customerId = updatedOrder.getUser().getId();

            String title;
            String body;

            if (OrderStatus.CONFIRMED.equals(status)) {
                title = "Order Confirmed 🎉";
                body = "Your order " + updatedOrder.getOrderNumber()
                        + " has been confirmed.";

            } else if (OrderStatus.PROCESSING.equals(status)) {
                title = "Order Processing 📦";
                body = "Your order " + updatedOrder.getOrderNumber()
                        + " is being processed.";

            } else if (OrderStatus.SHIPPED.equals(status)) {
                title = "Order Shipped 🚚";
                body = "Your order " + updatedOrder.getOrderNumber()
                        + " is on the way.";

            } else if (OrderStatus.DELIVERED.equals(status)) {
                title = "Order Delivered ✅";
                body = "Your order " + updatedOrder.getOrderNumber()
                        + " has been delivered.";

            } else if (OrderStatus.CANCELLED.equals(status)) {
                title = "Order Cancelled ❌";
                body = "Your order " + updatedOrder.getOrderNumber()
                        + " has been cancelled.";

            } else {
                title = "Order Updated";
                body = "Your order " + updatedOrder.getOrderNumber()
                        + " status changed to " + status + ".";
            }

            firebaseNotificationService.sendToUser(
                    customerId,
                    title,
                    body,
                    Map.of(
                            "type", "ORDER_STATUS",
                            "orderId", updatedOrder.getId().toString(),
                            "orderNumber", updatedOrder.getOrderNumber(),
                            "status", status));

            log.info(
                    "✅ END FCM: order={}, customerId={}, status={}",
                    updatedOrder.getOrderNumber(),
                    customerId,
                    status);

        } catch (Exception e) {
            log.error(
                    "❌ FCM ERROR: order={}, error={}",
                    updatedOrder.getOrderNumber(),
                    e.getMessage(),
                    e);
        }

        // =========================

        // FCM PUSH NOTIFICATION

        // =========================

        try {

            Long customerId =

                    updatedOrder.getUser().getId();

            String title;

            String body;

            switch (status) {

                case OrderStatus.CONFIRMED -> {

                    title = "Order Confirmed 🎉";

                    body = "Your order "

                            + updatedOrder.getOrderNumber()

                            + " has been confirmed.";

                }

                case OrderStatus.PROCESSING -> {

                    title = "Order Processing 📦";

                    body = "Your order "

                            + updatedOrder.getOrderNumber()

                            + " is being processed.";

                }

                case OrderStatus.SHIPPED -> {

                    title = "Order Shipped 🚚";

                    body = "Your order "

                            + updatedOrder.getOrderNumber()

                            + " is on the way.";

                }

                case OrderStatus.DELIVERED -> {

                    title = "Order Delivered ✅";

                    body = "Your order "

                            + updatedOrder.getOrderNumber()

                            + " has been delivered.";

                }

                case OrderStatus.CANCELLED -> {

                    title = "Order Cancelled ❌";

                    body = "Your order "

                            + updatedOrder.getOrderNumber()

                            + " has been cancelled.";

                }

                default -> {

                    title = "Order Updated";

                    body = "Your order "

                            + updatedOrder.getOrderNumber()

                            + " status changed to "

                            + status + ".";

                }

            }

            firebaseNotificationService.sendToUser(

                    customerId,

                    title,

                    body,

                    Map.of(

                            "type", "ORDER_STATUS",

                            "orderId",

                            updatedOrder.getId().toString(),

                            "orderNumber",

                            updatedOrder.getOrderNumber(),

                            "status",

                            status

                    )

            );

            log.info(

                    "FCM notification sent for order {} to user {} with status {}",

                    updatedOrder.getOrderNumber(),

                    customerId,

                    status

            );

        } catch (Exception e) {

            // FCM failure must not make order update fail

            log.warn(

                    "Failed to send FCM notification for order {}: {}",

                    updatedOrder.getOrderNumber(),

                    e.getMessage()

            );

        }

        return orderMapper.toResponse(updatedOrder);
    }

    @Override
    @Transactional
    public ResponseErrorTemplate cancelOrder(Long id, Long userId) {
        OrderDetail order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));

        if (!order.getUser().getId().equals(userId)) {
            throw new BadRequestException("User does not own this order");
        }

        if (!OrderStatus.PENDING.equals(order.getStatus())) {
            throw new BadRequestException("Only pending orders can be cancelled");
        }

        order.setStatus(OrderStatus.CANCELLED);
        if (order.getPayment() != null) {
            order.getPayment().setStatus(OrderStatus.REFUNDED);
            order.getOrderItems().forEach(
                    item -> inventoryRepository.increaseStock(item.getProductSku().getId(), item.getQuantity()));
        }
        OrderDetail cancelled = orderRepository.save(order);

        orderCancelationRepository.save(OrderCancelation.builder()
                .cancelationId(generateCancelationId())
                .orderId(cancelled.getId())
                .orderNo(cancelled.getOrderNumber())
                .customerId(cancelled.getUser().getId())
                .customerName(cancelled.getUser().getFullName())
                .cancelReason(CancelReason.CUSTOMER_REQUESTED)
                .cancelStatus(CancelStatus.CANCELED)
                .cancelSource("CUSTOMER")
                .cancelDate(LocalDateTime.now())
                .amount(cancelled.getTotalAmount())
                .currency("USD")
                .remark("Cancelled by customer")
                .createdBy(cancelled.getUser().getUsername())
                .build());

        try {
            emailNotificationService.sendOrderCancellationEmail(
                    cancelled.getUser().getEmail(),
                    cancelled.getOrderNumber(),
                    "Cancelled by customer");
        } catch (Exception e) {
            log.warn("Failed to send cancellation email for order {}: {}", cancelled.getOrderNumber(), e.getMessage());
        }

        return orderMapper.toResponse(cancelled);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getOrderDetailByUserId(Long userId, Long orderId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        OrderDetail order = orderRepository.findByIdAndUserIdWithFullDetail(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found with id: " + orderId + " for user: " + userId));
        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getOrderDetailHistory(Long userId, String status, LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return orderRepository.findOrderDetailHistory(userId, status, startDate, endDate, pageable)
                .map(orderMapper::toResponse);
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateCancelationId() {
        String cancelationId;
        do {
            cancelationId = "CNL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (orderCancelationRepository.existsByCancelationId(cancelationId));
        return cancelationId;
    }

    /**
     * Product prices are stored in USD. Default to USD when the client doesn't
     * choose,
     * and reject anything KHQR can't express.
     */
    private String resolveCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "USD";
        }
        String normalized = currency.trim().toUpperCase();
        if (!"USD".equals(normalized) && !"KHR".equals(normalized)) {
            throw new BadRequestException("Unsupported currency: " + currency + ". Only USD or KHR are supported.");
        }
        return normalized;
    }

    /**
     * Payment amounts are tracked in USD; KHQR needs the amount expressed in
     * whichever
     * currency the payer chose, so convert to KHR using the configured exchange
     * rate.
     */
    private double toBakongAmount(BigDecimal usdAmount, String currency) {
        if ("KHR".equals(currency)) {
            return usdAmount.multiply(BigDecimal.valueOf(usdToKhrRate))
                    .setScale(0, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        return usdAmount.doubleValue();
    }

    /**
     * ABA and ACLEDA don't have separate merchant integrations here — a KHQR code
     * generated
     * via Bakong is the national interbank standard, so any KHQR-compatible bank
     * app
     * (ABA Mobile, ACLEDA app, etc.) can already scan/open it. Treat them as the
     * same rail.
     */
    private boolean isKhqrPaymentMethod(String paymentMethod) {
        return OrderStatus.BAKONG.equalsIgnoreCase(paymentMethod)
                || OrderStatus.ABA.equalsIgnoreCase(paymentMethod)
                || OrderStatus.ACLEDA.equalsIgnoreCase(paymentMethod);
    }

    /**
     * Persist a
     * {@link com.example.learning_spring_security.Model.PaymentTransaction} for a
     * KHQR
     * order once the Bakong result is known. Runs in the caller's transaction, so
     * if the record
     * fails the whole verify/callback rolls back — a confirmed order must always
     * have a matching
     * transaction (refund logic depends on finding a SUCCESS transaction for the
     * order). Callers
     * already guard against re-entry via the CONFIRMED/CANCELLED short-circuits, so
     * this does not
     * create duplicate rows on gateway retries.
     */
    private void recordBakongTransaction(OrderDetail order, Payment payment,
            TransactionStatus status, String reason) {
        PaymentTransactionRequest txnRequest = PaymentTransactionRequest.builder()
                .orderId(order.getId())
                .customerId(order.getUser() != null ? order.getUser().getId() : null)
                .paymentMethod(PaymentMethod.fromString(payment.getPaymentMethod()))
                .amount(payment.getAmount())
                .currency(payment.getCurrency() != null ? payment.getCurrency() : "USD")
                .remarks(reason + " (bakong txn " + payment.getTransactionId() + ")")
                .build();

        paymentTransactionService.recordTransaction(txnRequest, status, "SYSTEM", reason);
    }

    private String resolveBakongDeepLink(String qr) {
        try {
            KHQRResponse<KHQRDeepLinkData> deepLinkResponse = bakongService.generateDeepLink(qr);
            if (deepLinkResponse != null
                    && deepLinkResponse.getKHQRStatus() != null
                    && deepLinkResponse.getKHQRStatus().getCode() == 0
                    && deepLinkResponse.getData() != null) {
                return deepLinkResponse.getData().getShortLink();
            }
            log.warn("Bakong deeplink generation returned a non-success status: {}", deepLinkResponse);
        } catch (Exception e) {
            log.warn("Failed to generate Bakong deeplink: {}", e.getMessage(), e);
        }
        return null;
    }

    @Override
    public ResponseErrorTemplate createOrderWithBakongPayment(Long userId, OrderRequest request) {
        if (!isKhqrPaymentMethod(request.getPaymentMethod())) {
            throw new BadRequestException("This method is only for Bakong/ABA/ACLEDA (KHQR) payments");
        }

        return createOrderFromCart(userId, request);
    }

    private ResponseErrorTemplate pushToBakong(ResponseErrorTemplate orderResponse) {
        OrderResponse orderData = (OrderResponse) orderResponse.object();

        try {
            OrderDetail pendingOrder = orderRepository.findByOrderNumber(orderData.getOrderNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            String currency = pendingOrder.getPayment().getCurrency();

            BakongRequest bakongRequest = BakongRequest.builder()
                    .currency(currency)
                    .amount(toBakongAmount(orderData.getTotalAmount(), currency))
                    .merchantName("E_Shop")
                    .merchantCity("PHNOM_PENH")
                    .merchantId("ESHOP001")
                    .acquiringBank("BAKONG")
                    .billNumber(orderData.getOrderNumber())
                    .storeLabel("E_SHOP_STORE")
                    .terminalLabel("TERMINAL_01")
                    .mobileNumber("012345678")
                    .purposeOfTransaction("Payment " + orderData.getOrderNumber())
                    .expirationTimestamp(15)
                    .build();

            KHQRResponse<KHQRData> bakongResponse = bakongService.generateQR(bakongRequest);

            if (bakongResponse != null
                    && bakongResponse.getKHQRStatus() != null
                    && bakongResponse.getKHQRStatus().getCode() == 0) {

                Payment payment = pendingOrder.getPayment();

                String md5 = bakongResponse.getData().getMd5(); // REAL KEY

                payment.setPaymentProvider(payment.getPaymentMethod());
                payment.setPaymentProviderResponse(bakongResponse.getData().getQr());
                payment.setCode(paymentCodeGenerator.generatePaymentCode());
                payment.setCodeOrder(generateOrderNumber());

                payment.setTransactionId(md5);

                String qr = bakongResponse.getData().getQr();
                String paymentUrl = resolveBakongDeepLink(qr);
                payment.setQrCode(qr);
                payment.setPaymentUrl(paymentUrl);

                orderRepository.save(pendingOrder);

                orderData.setPayment(PaymentMapper.toResponse(payment));
                orderData.setQrCode(qr);
                orderData.setPaymentUrl(paymentUrl);
            } else {
                log.error("Bakong QR generation returned a non-success status for order {}: {}",
                        orderData.getOrderNumber(), bakongResponse);
                return ResponseErrorTemplate.builder()
                        .message(
                                "Order created, but Bakong QR generation failed. Retry payment via POST /api/v1/orders/bakong/initiate?orderId="
                                        + orderData.getId())
                        .code(orderResponse.code())
                        .object(orderData)
                        .build();
            }

        } catch (Exception e) {
            log.error("Failed to generate Bakong QR for order {}: {}", orderData.getOrderNumber(), e.getMessage(), e);
            return ResponseErrorTemplate.builder()
                    .message("Order created, but Bakong QR generation failed: " + e.getMessage()
                            + ". Retry payment via POST /api/v1/orders/bakong/initiate?orderId=" + orderData.getId())
                    .code(orderResponse.code())
                    .object(orderData)
                    .build();
        }

        return orderResponse;
    }

    @Override
    @Transactional
    public ResponseErrorTemplate initiateBakongPayment(Long orderId) {
        OrderDetail order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (!isKhqrPaymentMethod(order.getPayment().getPaymentMethod())) {
            throw new BadRequestException("Order does not use a Bakong/ABA/ACLEDA (KHQR) payment method");
        }

        if (!OrderStatus.PENDING.equals(order.getStatus())) {
            throw new BadRequestException("Order is not in pending status");
        }

        try {
            String currency = order.getPayment().getCurrency();

            BakongRequest bakongRequest = BakongRequest.builder()
                    .currency(currency)
                    .amount(toBakongAmount(order.getTotalAmount(), currency))
                    .merchantName("E_Shop")
                    .merchantCity("PHNOM PENH")
                    .merchantId("ESHOP001")
                    .acquiringBank("NBC")
                    .billNumber(order.getOrderNumber())
                    .storeLabel("E_SHOP_STORE")
                    .terminalLabel("TERMINAL_01")
                    .mobileNumber("097328636")
                    .purposeOfTransaction("Payment " + order.getOrderNumber())
                    .expirationTimestamp(15)
                    .build();

            KHQRResponse<KHQRData> bakongResponse = bakongService.generateQR(bakongRequest);

            if (bakongResponse != null
                    && bakongResponse.getKHQRStatus() != null
                    && bakongResponse.getKHQRStatus().getCode() == 0) {

                Payment payment = order.getPayment();

                String md5 = bakongResponse.getData().getMd5();

                payment.setPaymentProvider(payment.getPaymentMethod());
                payment.setPaymentProviderResponse(bakongResponse.getData().getQr());

                payment.setTransactionId(md5);

                String qr = bakongResponse.getData().getQr();
                String paymentUrl = resolveBakongDeepLink(qr);
                payment.setQrCode(qr);
                payment.setPaymentUrl(paymentUrl);

                orderRepository.save(order);

                Map<String, Object> responseData = new HashMap<>();
                responseData.put("orderId", order.getId());
                responseData.put("orderNumber", order.getOrderNumber());
                responseData.put("qrCode", qr);
                responseData.put("paymentUrl", paymentUrl);
                responseData.put("amount", order.getTotalAmount());
                responseData.put("expiresIn", "15 minutes");

                return ResponseErrorTemplate.builder()
                        .message("Bakong payment initiated successfully")
                        .object(responseData)
                        .build();
            } else {
                throw new RuntimeException("Failed to generate QR code: " + bakongResponse);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initiate Bakong payment: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ResponseErrorTemplate verifyBakongPayment(Long orderId, String md5) {

        OrderDetail order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        Payment payment = order.getPayment();

        if (!isKhqrPaymentMethod(payment.getPaymentMethod())) {
            throw new BadRequestException("Not a Bakong/ABA/ACLEDA (KHQR) payment");
        }

        // Already confirmed by a previous call — don't re-verify or re-save.
        if (OrderStatus.CONFIRMED.equals(order.getStatus())) {
            return ResponseErrorTemplate.builder()
                    .message("Payment already verified")
                    .object(Map.of("orderId", orderId, "status", OrderStatus.CONFIRMED))
                    .build();
        }

        String bakongMd5 = payment.getTransactionId();

        if (bakongMd5 == null || bakongMd5.isBlank()) {
            bakongMd5 = md5;
        }

        if (bakongMd5 == null || bakongMd5.isBlank()) {
            return ResponseErrorTemplate.builder()
                    .message("Missing MD5 for verification")
                    .object(Map.of("orderId", orderId))
                    .build();
        }

        BakongResponse response = bakongService.checkTransactionByMD5(
                new CheckTransactionRequest(bakongMd5));

        if (response != null && response.isSuccess() && response.getData() != null) {

            order.setStatus(OrderStatus.CONFIRMED);
            payment.setStatus(OrderStatus.COMPLETED);

            orderRepository.save(order);

            // Money confirmed received — record the successful PaymentTransaction.
            recordBakongTransaction(order, payment, TransactionStatus.SUCCESS, "Bakong payment verified");

            // =========================

            // PAYMENT SUCCESS FCM

            // =========================

            try {

                Long customerId = order.getUser().getId();

                log.info(

                        "🔥 START PAYMENT SUCCESS FCM: order={}, customerId={}",

                        order.getOrderNumber(),

                        customerId

                );

                firebaseNotificationService.sendToUser(

                        customerId,

                        "Payment Successful 💳",

                        "Payment for order "

                                + order.getOrderNumber()

                                + " was successful.",

                        Map.of(

                                "type", "PAYMENT_SUCCESS",

                                "orderId", order.getId().toString(),

                                "orderNumber", order.getOrderNumber(),

                                "orderStatus", OrderStatus.CONFIRMED,

                                "paymentStatus", OrderStatus.COMPLETED

                        )

                );

                log.info(

                        "✅ PAYMENT SUCCESS FCM SENT: order={}, customerId={}",

                        order.getOrderNumber(),

                        customerId

                );

            } catch (Exception e) {

                log.warn(

                        "Failed to send payment success FCM. order={}, error={}",

                        order.getOrderNumber(),

                        e.getMessage()

                );

            }

            return ResponseErrorTemplate.builder()
                    .message("Payment verified successfully")
                    .object(Map.of(
                            "orderId", orderId,
                            "status", OrderStatus.CONFIRMED))
                    .build();
        }

        return ResponseErrorTemplate.builder()
                .message("Payment verification failed")
                .object(Map.of(
                        "orderId", orderId,
                        "status", OrderStatus.FAILED))
                .build();
    }

    @Override
    @Transactional
    public ResponseErrorTemplate processBakongPaymentCallback(String orderNumber, String transactionId, String status) {
        OrderDetail order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with number: " + orderNumber));

        if (!isKhqrPaymentMethod(order.getPayment().getPaymentMethod())) {
            throw new BadRequestException("Order does not use a Bakong/ABA/ACLEDA (KHQR) payment method");
        }

        // Gateway retried a callback we already applied — don't reprocess (e.g. double
        // stock restock).
        if (OrderStatus.CONFIRMED.equals(order.getStatus()) || OrderStatus.CANCELLED.equals(order.getStatus())) {
            return ResponseErrorTemplate.builder()
                    .message("Callback already processed for this order")
                    .object(Map.of(
                            "orderNumber", orderNumber,
                            "orderStatus", order.getStatus(),
                            "paymentStatus", order.getPayment().getStatus()))
                    .build();
        }

        Payment payment = order.getPayment();

        // Store the real Bakong transaction ID
        payment.setTransactionId(transactionId); // keep generic field updated too

        TransactionStatus transactionStatus;
        switch (status.toUpperCase()) {
            case "SUCCESS":
            case "COMPLETED":
                order.setStatus(OrderStatus.CONFIRMED);
                payment.setStatus(OrderStatus.COMPLETED);
                transactionStatus = TransactionStatus.SUCCESS;
                break;
            case "FAILED":
            case "CANCELLED":
                order.setStatus(OrderStatus.CANCELLED);
                payment.setStatus(OrderStatus.FAILED);
                order.getOrderItems().forEach(
                        item -> inventoryRepository.increaseStock(item.getProductSku().getId(), item.getQuantity()));
                transactionStatus = TransactionStatus.FAILED;
                break;
            case "PENDING":
            default:
                order.setStatus(OrderStatus.PENDING);
                payment.setStatus(OrderStatus.PENDING);
                transactionStatus = null; // still pending — nothing settled yet, don't record
                break;
        }

        orderRepository.save(order);

        // Record the settled (SUCCESS/FAILED) outcome as a PaymentTransaction. PENDING
        // is skipped
        // so repeated pending callbacks don't spam the table with unsettled rows.
        if (transactionStatus != null) {
            recordBakongTransaction(order, payment, transactionStatus,
                    "Bakong callback: " + status.toUpperCase());
        }

        return ResponseErrorTemplate.builder()
                .message("Payment callback processed successfully")
                .object(Map.of(
                        "orderNumber", orderNumber,
                        "transactionId", transactionId,
                        "orderStatus", order.getStatus(),
                        "paymentStatus", payment.getStatus()))
                .build();
    }

    @Transactional(readOnly = true)
    @Override
    public ResponseErrorTemplate getOrderStatusSummary() {

        OrderStatusSummaryResponse response = orderRepository.getOrderStatusSummary();

        return ResponseErrorTemplate.success(
                "Order status summary retrieved successfully",
                response);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getOrderItemsByOrderId(Long orderId) {

        OrderDetail order = orderRepository.findByIdWithFullDetail(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        List<OrderItemResponse> items = order.getOrderItems()
                .stream()
                .map(orderItemMapper::toResponse)
                .toList();

        return ResponseErrorTemplate.success(
                "Order items retrieved successfully",
                items);
    }
}