package com.ecommerce.product.service;

import com.ecommerce.product.dto.AssistantChatRequest;
import com.ecommerce.product.dto.AssistantChatResponse;
import com.ecommerce.product.dto.AssistantProductCard;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ShoppingAssistantService {
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "for", "to", "of", "in", "on", "with", "my", "me",
            "i", "is", "are", "please", "show", "find", "get", "want", "need", "looking",
            "buy", "best", "good", "great", "some", "any", "can", "you", "suggest", "recommend",
            "compare", "vs", "versus", "between", "which", "what", "whats", "how", "help",
            "under", "below", "less", "than", "within", "budget", "rs", "inr", "rupees"
    );

    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("tv", List.of("tv", "television", "oled", "bravia")),
            Map.entry("laptop", List.of("laptop", "notebook")),
            Map.entry("phone", List.of("phone", "smartphone", "mobile")),
            Map.entry("headphone", List.of("headphone", "headset", "earphone")),
            Map.entry("watch", List.of("watch", "smartwatch")),
            Map.entry("camera", List.of("camera", "eos")),
            Map.entry("speaker", List.of("speaker", "bluetooth")),
            Map.entry("shoes", List.of("shoes", "running")),
            Map.entry("bag", List.of("bag", "handbag")),
            Map.entry("keyboard", List.of("keyboard")),
            Map.entry("mouse", List.of("mouse")),
            Map.entry("tablet", List.of("tablet")),
            Map.entry("console", List.of("console", "playstation", "ps5")),
            Map.entry("vacuum", List.of("vacuum", "cleaner"))
    );

    private static final Pattern BUDGET_PATTERN = Pattern.compile(
            "(?:under|below|less than|within|upto|up to|max|budget of)?\\s*(?:₹|rs\\.?|inr)?\\s*(\\d{3,7})k?",
            Pattern.CASE_INSENSITIVE
    );

    private final ProductRepository productRepository;
    private final ImageStorageService imageStorageService;
    private final Map<String, List<String>> sessions = new ConcurrentHashMap<>();
    private final NumberFormat inr = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    public ShoppingAssistantService(ProductRepository productRepository, ImageStorageService imageStorageService) {
        this.productRepository = productRepository;
        this.imageStorageService = imageStorageService;
    }

    public AssistantChatResponse chat(AssistantChatRequest request) {
        return chat(request, null);
    }

    public AssistantChatResponse chat(AssistantChatRequest request, String authenticatedUser) {
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String conversationKey = conversationKey(request, authenticatedUser);
        sessions.computeIfAbsent(conversationKey, key -> new ArrayList<>()).add(message);

        String lower = message.toLowerCase(Locale.ROOT);
        AssistantChatResponse response = new AssistantChatResponse();

        if (isGreeting(lower) && !looksLikeProductQuery(lower)) {
            response.setIntent("greeting");
            response.setReply("Hi, I'm Mira — your MyStore shopping assistant. I can find products, compare items side by side, suggest the best pick for your budget, and help with orders, delivery, returns, and payments.");
            response.setSuggestions(List.of("Compare Sony TVs", "Best laptop under 90000", "Show running shoes", "How do returns work?"));
            return response;
        }

        if (isHelpIntent(lower)) {
            return helpResponse(lower, response);
        }

        if (isCompareIntent(lower)) {
            return compareResponse(message, response);
        }

        return searchResponse(message, response);
    }

    private AssistantChatResponse helpResponse(String lower, AssistantChatResponse response) {
        response.setIntent("help");
        response.setSuggestions(List.of("Track my order", "Compare headphones", "Best gifts under 2000"));

        if (lower.contains("return") || lower.contains("refund") || lower.contains("replace")) {
            response.setReply("Returns are easy. You can request a return from My Orders within 7 days of delivery if the item is unused and in original packaging. Refunds go back to the original payment method in 5–7 business days. COD orders are refunded to your UPI or bank account.");
            return response;
        }
        if (lower.contains("deliver") || lower.contains("shipping") || lower.contains("track")) {
            response.setReply("Standard delivery takes about 4–6 business days after the order is confirmed. You'll get an email confirmation as soon as the order is placed, and you can follow live status from My Orders. Delivery is a flat ₹99 at checkout.");
            return response;
        }
        if (lower.contains("pay") || lower.contains("cod") || lower.contains("razorpay") || lower.contains("upi") || lower.contains("card")) {
            response.setReply("You can pay with Card, UPI, Net Banking, or Cash on Delivery. Online payments are processed securely with Razorpay. After a successful payment you'll receive an order confirmation email with item details and the estimated delivery date.");
            return response;
        }
        if (lower.contains("order") || lower.contains("cancel")) {
            response.setReply("Open My Orders to view status, items, and totals. If an order is still pending or confirmed and not shipped, contact support from the order page to request a cancellation. You'll also get a confirmation email when the order is placed.");
            return response;
        }
        if (lower.contains("wishlist")) {
            response.setReply("Tap the heart on any product to save it to your Wishlist. You can add those items to cart later from the Wishlist page after you sign in.");
            return response;
        }

        response.setReply("I can help you shop like this:\n• Search — “show me laptops”\n• Budget — “headphones under 5000”\n• Compare — “compare Sony TVs” or “laptop vs tablet”\n• Support — delivery, returns, payments, and orders.\nWhat would you like to do?");
        return response;
    }

    private AssistantChatResponse compareResponse(String message, AssistantChatResponse response) {
        List<Product> catalog = loadCatalog();
        List<ScoredProduct> scored = scoreProducts(message, catalog);
        List<Product> picks = scored.stream()
                .filter(item -> item.score >= 2)
                .limit(4)
                .map(item -> item.product)
                .collect(Collectors.toCollection(ArrayList::new));

        if (picks.size() < 2) {
            picks = catalog.stream()
                    .filter(product -> categoryHint(message) == null || categoryHint(message).equalsIgnoreCase(product.getCategory()))
                    .sorted(Comparator.comparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo)))
                    .limit(3)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (picks.size() < 2) {
            response.setIntent("compare");
            response.setReply("I need at least two matching products to compare. Try something like “compare Sony TVs” or “laptop vs tablet”.");
            response.setSuggestions(List.of("Compare Sony TVs", "Laptop vs tablet", "Compare headphones"));
            return response;
        }

        Product winner = pickWinner(picks);
        response.setIntent("compare");
        response.setProducts(picks.stream().map(this::toCard).toList());
        response.setComparisonHeaders(List.of("Feature", "Details"));
        response.setComparisonRows(buildComparison(picks));
        response.setReply("Here's a side-by-side look at " + joinNames(picks) + ". My pick right now is **" + winner.getName() + "** at " + inr.format(winner.getPrice()) + " — it balances price, availability, and what you asked for. Add any item below to your cart, or ask me to compare a different pair.");
        response.setSuggestions(List.of("Show cheaper options", "Best in " + safe(picks.get(0).getCategory()), "Delivery information"));
        return response;
    }

    private AssistantChatResponse searchResponse(String message, AssistantChatResponse response) {
        List<Product> catalog = loadCatalog();
        BigDecimal budget = extractBudget(message);
        String category = categoryHint(message);
        List<ScoredProduct> scored = scoreProducts(message, catalog);

        List<Product> matches = scored.stream()
                .filter(item -> item.score > 0)
                .map(item -> item.product)
                .filter(product -> budget == null || (product.getPrice() != null && product.getPrice().compareTo(budget) <= 0))
                .filter(product -> category == null || category.equalsIgnoreCase(product.getCategory()))
                .limit(6)
                .collect(Collectors.toCollection(ArrayList::new));

        if (matches.isEmpty() && budget != null) {
            matches = catalog.stream()
                    .filter(product -> product.getPrice() != null && product.getPrice().compareTo(budget) <= 0)
                    .sorted(Comparator.comparing(Product::getPrice).reversed())
                    .limit(6)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (matches.isEmpty() && category != null) {
            matches = catalog.stream()
                    .filter(product -> category.equalsIgnoreCase(product.getCategory()))
                    .sorted(Comparator.comparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo)))
                    .limit(6)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (matches.isEmpty()) {
            response.setIntent("search");
            response.setReply("I couldn't find a close match for that. Try a product type, brand, or budget — for example “air fryer”, “compare smartwatches”, or “gifts under 1500”.");
            response.setSuggestions(List.of("Show electronics", "Best under 2000", "Compare TVs"));
            response.setProducts(catalog.stream().sorted(Comparator.comparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo))).limit(3).map(this::toCard).toList());
            return response;
        }

        Product top = matches.get(0);
        String budgetText = budget == null ? "" : " within " + inr.format(budget);
        response.setIntent("search");
        response.setProducts(matches.stream().map(this::toCard).toList());
        response.setReply("I found " + matches.size() + " good match" + (matches.size() == 1 ? "" : "es") + budgetText + ". **" + top.getName() + "** at " + inr.format(top.getPrice()) + " is a strong starting point — " + safe(top.getDescription()) + " Want me to compare the top options?");
        response.setSuggestions(List.of(
                "Compare these",
                "Cheaper than " + inr.format(top.getPrice()),
                "More in " + safe(top.getCategory())
        ));
        return response;
    }

    private List<Product> loadCatalog() {
        List<Product> products = productRepository.findAll();
        products.forEach(product -> {
            if (product.getImageName() != null && (product.getImagePath() == null || product.getImagePath().isBlank())) {
                product.setImagePath(imageStorageService.getImageUrl(product.getImageName()));
            }
        });
        return products;
    }

    private List<ScoredProduct> scoreProducts(String message, List<Product> catalog) {
        List<String> tokens = tokenize(message);
        List<ScoredProduct> scored = new ArrayList<>();
        for (Product product : catalog) {
            int score = 0;
            String haystack = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getCategory())).toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (haystack.contains(token)) {
                    score += 3;
                }
                for (List<String> group : SYNONYMS.values()) {
                    if (group.contains(token) && group.stream().anyMatch(haystack::contains)) {
                        score += 2;
                    }
                }
            }
            if (product.getStockQuantity() != null && product.getStockQuantity() > 0) {
                score += 1;
            }
            scored.add(new ScoredProduct(product, score));
        }
        scored.sort(Comparator.comparingInt((ScoredProduct item) -> item.score).reversed()
                .thenComparing(item -> item.product.getPrice(), Comparator.nullsLast(BigDecimal::compareTo)));
        return scored;
    }

    private List<Map<String, String>> buildComparison(List<Product> products) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("Product", products.stream().map(Product::getName).toList()));
        rows.add(row("Price", products.stream().map(product -> inr.format(product.getPrice())).toList()));
        rows.add(row("Category", products.stream().map(product -> safe(product.getCategory())).toList()));
        rows.add(row("Stock", products.stream().map(product -> product.getStockQuantity() == null ? "—" : product.getStockQuantity() + " left").toList()));
        rows.add(row("Best for", products.stream().map(this::bestFor).toList()));
        rows.add(row("Why consider", products.stream().map(product -> safe(product.getDescription())).toList()));
        return rows;
    }

    private Map<String, String> row(String feature, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Feature", feature);
        for (int i = 0; i < values.size(); i++) {
            row.put("item" + (i + 1), values.get(i));
        }
        return row;
    }

    private Product pickWinner(List<Product> products) {
        return products.stream().min(Comparator
                .comparing((Product product) -> product.getStockQuantity() == null || product.getStockQuantity() <= 0)
                .thenComparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo))
        ).orElse(products.get(0));
    }

    private String bestFor(Product product) {
        BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
        if (price.compareTo(BigDecimal.valueOf(2000)) < 0) return "Everyday value";
        if (price.compareTo(BigDecimal.valueOf(15000)) < 0) return "Most shoppers";
        return "Premium pick";
    }

    private AssistantProductCard toCard(Product product) {
        AssistantProductCard card = new AssistantProductCard();
        card.setId(product.getId());
        card.setName(product.getName());
        card.setDescription(product.getDescription());
        card.setPrice(product.getPrice());
        card.setStockQuantity(product.getStockQuantity());
        card.setCategory(product.getCategory());
        card.setImagePath(product.getImagePath());
        card.setHighlight(bestFor(product));
        return card;
    }

    private String conversationKey(AssistantChatRequest request, String authenticatedUser) {
        if (StringUtils.hasText(authenticatedUser)) {
            return "user:" + authenticatedUser.trim();
        }
        if (StringUtils.hasText(request.getUserId())) {
            return "user:" + request.getUserId().trim();
        }
        if (StringUtils.hasText(request.getSessionId())) {
            return "guest:" + request.getSessionId().trim();
        }
        return "guest:" + java.util.UUID.randomUUID();
    }

    private boolean isGreeting(String lower) {
        return lower.matches("hi|hello|hey|hola|namaste|good (morning|afternoon|evening)|what can you do|who are you.*");
    }

    private boolean looksLikeProductQuery(String lower) {
        return lower.contains("compare") || lower.contains("under") || categoryHint(lower) != null;
    }

    private boolean isHelpIntent(String lower) {
        boolean orderHelp = lower.contains("order") && (lower.contains("track") || lower.contains("my")
                || lower.contains("status") || lower.contains("cancel") || lower.contains("where"));
        return lower.contains("return") || lower.contains("refund") || lower.contains("deliver")
                || lower.contains("shipping") || lower.contains("track order") || lower.contains("tracking")
                || orderHelp || lower.contains("cancel") || lower.contains("payment")
                || lower.contains("cash on delivery") || lower.contains("cod")
                || lower.contains("razorpay") || lower.contains("wishlist") || lower.contains("help")
                || lower.contains("support") || lower.contains("how do");
    }

    private boolean isCompareIntent(String lower) {
        return lower.contains("compare") || lower.contains(" vs ") || lower.contains("versus")
                || lower.contains("difference") || lower.contains("which is better") || lower.contains("side by side");
    }

    private String categoryHint(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("electronic") || lower.contains("gadget") || lower.contains("tv") || lower.contains("laptop") || lower.contains("phone")) {
            return "Electronics";
        }
        if (lower.contains("fashion") || lower.contains("dress") || lower.contains("jeans") || lower.contains("shoes") || lower.contains("jacket")) {
            return "Fashion";
        }
        if (lower.contains("home") || lower.contains("kitchen") || lower.contains("lamp") || lower.contains("vacuum") || lower.contains("air fryer")) {
            return "Home";
        }
        if (lower.contains("sport") || lower.contains("fitness") || lower.contains("yoga") || lower.contains("cricket") || lower.contains("football")) {
            return "Sports";
        }
        if (lower.contains("grocery") || lower.contains("shampoo") || lower.contains("lotion") || lower.contains("coffee") || lower.contains("rice")) {
            return "Groceries";
        }
        if (lower.contains("toy") || lower.contains("teddy") || lower.contains("puzzle")) {
            return "Toys";
        }
        if (lower.contains("book") || lower.contains("novel") || lower.contains("cookbook")) {
            return "Books";
        }
        return null;
    }

    private BigDecimal extractBudget(String message) {
        Matcher matcher = BUDGET_PATTERN.matcher(message.replace(",", ""));
        BigDecimal found = null;
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null) continue;
            long value = Long.parseLong(raw);
            if (matcher.group().toLowerCase(Locale.ROOT).contains("k") && value < 1000) {
                value *= 1000;
            }
            if (value >= 50) {
                found = BigDecimal.valueOf(value);
            }
        }
        return found;
    }

    private List<String> tokenize(String message) {
        return Arrays.stream(message.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 1 && !STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private String joinNames(List<Product> products) {
        return products.stream().map(Product::getName).collect(Collectors.joining(", "));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredProduct(Product product, int score) {}
}
