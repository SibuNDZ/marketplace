package com.marketplace.api.email;

import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderItem;
import com.marketplace.api.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.marketplace.api.email.EmailService.escape;

/**
 * Composes and sends order lifecycle emails through the shared
 * {@link EmailService} transport, inheriting its swallow-and-log contract:
 * nothing here throws, and a false return (provider down, no API key) is a
 * log line, not a failed order.
 *
 * Callers pass a FULLY FETCHED order graph (user, items, products, vendors —
 * see OrderRepository.findWithItemsAndVendorsById); this class runs outside
 * any transaction on an async thread and cannot trigger lazy loads.
 */
@Service
public class OrderEmailService {

    private static final Logger log = LoggerFactory.getLogger(OrderEmailService.class);

    private final EmailService emailService;

    public OrderEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * PAID: buyer confirmation plus one "you have an order" email per distinct
     * vendor, each showing only that vendor's line items and the shipping
     * address they must dispatch to.
     */
    public void sendOrderPaidEmails(Order order) {
        User buyer = order.getUser();
        emailService.send(buyer.getEmail(),
                "Your eRestyu order " + order.getOrderNumber() + " is confirmed",
                wrap("Hi " + escape(buyer.getFirstName()) + ",",
                        "Thank you for your order! Payment was received and the vendors "
                                + "have been notified. We will email you again when your items ship.",
                        itemsTable(order.getOrderItems())
                                + totalRow("Order total", order.getTotalAmount())
                                + addressBlock(order, "Delivery address")));

        for (Map.Entry<User, List<OrderItem>> entry : itemsByVendor(order).entrySet()) {
            User vendor = entry.getKey();
            List<OrderItem> items = entry.getValue();
            BigDecimal vendorTotal = items.stream()
                    .map(i -> i.getPriceAtPurchase().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            emailService.send(vendor.getEmail(),
                    "New paid order " + order.getOrderNumber() + " on eRestyu",
                    wrap("Hi " + escape(vendor.getFirstName()) + ",",
                            "A customer has paid for the items below. Please prepare them "
                                    + "for dispatch to the address at the bottom of this email.",
                            itemsTable(items)
                                    + totalRow("Your items total", vendorTotal)
                                    + addressBlock(order, "Ship to")));
        }
    }

    /** SHIPPED: buyer notification. */
    public void sendOrderShippedEmail(Order order) {
        User buyer = order.getUser();
        emailService.send(buyer.getEmail(),
                "Your eRestyu order " + order.getOrderNumber() + " has shipped",
                wrap("Hi " + escape(buyer.getFirstName()) + ",",
                        "Good news: your order is on its way to the address below.",
                        itemsTable(order.getOrderItems())
                                + addressBlock(order, "Delivery address")));
    }

    /**
     * Groups line items by vendor, preserving item order. An item whose
     * product was hard-deleted (or predates vendor assignment) has no vendor
     * to notify; it still appears in the buyer's emails, so dropping it here
     * only loses a notification that cannot be addressed anyway.
     */
    private Map<User, List<OrderItem>> itemsByVendor(Order order) {
        Map<User, List<OrderItem>> byVendor = new LinkedHashMap<>();
        for (OrderItem item : order.getOrderItems()) {
            User vendor = item.getProduct() == null ? null : item.getProduct().getVendor();
            if (vendor == null) {
                log.warn("Order {} item '{}' has no vendor (product deleted?) — no vendor email",
                        order.getOrderNumber(), item.getProductNameAtPurchase());
                continue;
            }
            byVendor.computeIfAbsent(vendor, v -> new ArrayList<>()).add(item);
        }
        return byVendor;
    }

    private static String itemsTable(List<OrderItem> items) {
        StringBuilder rows = new StringBuilder();
        for (OrderItem item : items) {
            rows.append("""
                    <tr>
                      <td style="padding:8px 12px 8px 0;font-size:14px">%s</td>
                      <td style="padding:8px 12px;font-size:14px;text-align:center">%d</td>
                      <td style="padding:8px 0;font-size:14px;text-align:right">%s</td>
                    </tr>
                    """.formatted(escape(item.getProductNameAtPurchase()),
                                  item.getQuantity(),
                                  rand(item.getPriceAtPurchase())));
        }
        return """
               <table style="width:100%%;border-collapse:collapse;margin:0 0 8px">
                 <tr style="border-bottom:1px solid #e5e5e5">
                   <th style="padding:0 12px 8px 0;font-size:13px;text-align:left;color:#666">Item</th>
                   <th style="padding:0 12px 8px;font-size:13px;text-align:center;color:#666">Qty</th>
                   <th style="padding:0 0 8px;font-size:13px;text-align:right;color:#666">Price</th>
                 </tr>
                 %s
               </table>
               """.formatted(rows.toString());
    }

    private static String totalRow(String label, BigDecimal amount) {
        return """
               <p style="font-size:15px;font-weight:700;text-align:right;margin:0 0 24px;\
               border-top:1px solid #e5e5e5;padding-top:8px">%s: %s</p>
               """.formatted(escape(label), rand(amount));
    }

    /**
     * The structured V12 address. addressLine1 null means the buyer never
     * completed the pay-time address form, which cannot happen for a PAID
     * order in the normal flow; the fallback keeps a manually driven or
     * legacy order from rendering a block of the word "null".
     */
    private static String addressBlock(Order order, String heading) {
        if (order.getAddressLine1() == null) {
            return "<p style=\"font-size:13px;color:#888\">No shipping address on file "
                    + "for this order. Contact support before dispatching.</p>";
        }
        String line2 = order.getAddressLine2() == null || order.getAddressLine2().isBlank()
                ? "" : escape(order.getAddressLine2()) + "<br>";
        return """
               <div style="background:#f7f7f5;border-radius:8px;padding:16px;margin:0 0 8px">
                 <div style="font-size:13px;font-weight:700;color:#666;margin-bottom:8px">%s</div>
                 <div style="font-size:14px;line-height:1.6">
                   %s<br>%s<br>%s%s, %s %s<br>%s
                 </div>
               </div>
               """.formatted(escape(heading),
                             escape(order.getRecipientName()),
                             escape(order.getAddressLine1()),
                             line2,
                             escape(order.getCity()),
                             escape(order.getProvince()),
                             escape(order.getPostalCode()),
                             escape(order.getPhone()));
    }

    private static String rand(BigDecimal amount) {
        return "R" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Same visual shell as the auth emails: logo, greeting, body, no button. */
    private static String wrap(String greeting, String intro, String body) {
        return """
               <div style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;\
               max-width:520px;margin:0 auto;padding:32px 24px;color:#1a1a1a">
                 <div style="font-size:24px;font-weight:800;color:#e2542c;margin-bottom:24px">eRestyu</div>
                 <p style="font-size:16px;margin:0 0 8px">%s</p>
                 <p style="font-size:15px;line-height:1.5;margin:0 0 24px">%s</p>
                 %s
               </div>
               """.formatted(greeting, escape(intro), body);
    }
}
