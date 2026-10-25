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
            "under", "below", "less", "than", "within", "budget", "rs", "inr", "rupees",
            "these", "those", "them", "this", "that", "options", "items", "ones", "top",
            "side", "pair", "picks", "pick");

    private static final List<ProductType> PRODUCT_TYPES = List.of(
            new ProductType("tv", "Electronics",
                    List.of("television", "televisions", "oled", "bravia", "smart tv", "led tv", "tvs", "tv")),
            new ProductType("laptop", "Electronics", List.of("notebook", "notebooks", "laptops", "laptop")),
            new ProductType("phone", "Electronics",
                    List.of("smartphone", "smartphones", "mobile", "mobiles", "iphone", "phones", "phone")),
            new ProductType("headphone", "Electronics",
                    List.of("headphones", "headphone", "headset", "earphones", "earphone", "earbuds")),
            new ProductType("smartwatch", "Electronics",
                    List.of("smartwatch", "smart watch", "fitness tracker", "fitness band")),
            new ProductType("watch", "Fashion",
                    List.of("chronograph", "wrist watch", "wristwatch", "watches", "watch")),
            new ProductType("camera", "Electronics", List.of("cameras", "camera", "mirrorless", "dslr", "eos")),
            new ProductType("speaker", "Electronics", List.of("speakers", "speaker", "soundbar")),
            new ProductType("tablet", "Electronics", List.of("tablets", "tablet", "ipad")),
            new ProductType("console", "Electronics", List.of("playstation", "xbox", "console", "ps5")),
            new ProductType("keyboard", "Electronics", List.of("keyboards", "keyboard")),
            new ProductType("mouse", "Electronics", List.of("mice", "mouse")),
            new ProductType("shoes", "Fashion",
                    List.of("running shoes", "sneakers", "sneaker", "footwear", "shoes", "shoe")),
            new ProductType("bag", "Fashion", List.of("handbags", "handbag", "bags", "bag")),
            new ProductType("dress", "Fashion", List.of("dresses", "dress")),
            new ProductType("jeans", "Fashion", List.of("denim", "jeans")),
            new ProductType("jacket", "Fashion", List.of("jackets", "jacket")),
            new ProductType("biscuit", "Groceries", List.of("biscuits", "biscuit", "cookies", "cookie", "snacks")),
            new ProductType("coffee", "Groceries", List.of("coffee", "arabica")),
            new ProductType("rice", "Groceries", List.of("basmati", "rice")),
            new ProductType("shampoo", "Groceries", List.of("shampoo")),
            new ProductType("air fryer", "Home", List.of("air fryer", "airfryer")),
            new ProductType("vacuum", "Home", List.of("vacuum", "cleaner")),
            new ProductType("lamp", "Home", List.of("desk lamp", "floor lamp", "lamps", "lamp")),
            new ProductType("cricket", "Sports", List.of("cricket bat", "cricket")),
            new ProductType("football", "Sports", List.of("football", "soccer")),
            new ProductType("yoga", "Sports", List.of("yoga mat", "yoga")),
            new ProductType("book", "Books", List.of("cookbook", "novel", "fiction", "science book", "books", "book")));

    private static final Pattern BUDGET_PATTERN = Pattern.compile(
            "(?:under|below|less than|within|upto|up to|max|budget of)?\\s*(?:₹|rs\\.?|inr)?\\s*(\\d{3,7})k?",
            Pattern.CASE_INSENSITIVE);

    private final ProductRepository productRepository;
    private final ImageStorageService imageStorageService;
    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();
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
        ConversationState state = sessions.computeIfAbsent(conversationKey(request, authenticatedUser),
                key -> new ConversationState());
        state.messages.add(message);

        String lower = message.toLowerCase(Locale.ROOT);
        AssistantChatResponse response = new AssistantChatResponse();

        if (isGreeting(lower) && !looksLikeProductQuery(lower)) {
            response.setIntent("greeting");
            response.setReply(
                    "Hi, I'm Mira — your MyStore shopping assistant. I can find products, compare items side by side, suggest the best pick for your budget, and help with orders, delivery, returns, and payments.");
            response.setSuggestions(List.of("Compare Sony TVs", "Best laptop under 90000", "Show running shoes",
                    "How do returns work?"));
            return response;
        }

        if (isHelpIntent(lower) && !looksLikeProductQuery(lower)) {
            return helpResponse(lower, response);
        }

        if (isCompareIntent(lower) || isCompareFollowUp(lower)) {
            return compareResponse(message, state, response);
        }

        return searchResponse(message, state, response);
    }

    private AssistantChatResponse helpResponse(String lower, AssistantChatResponse response) {
        response.setIntent("help");
        response.setSuggestions(List.of("Track my order", "Compare headphones", "Best gifts under 2000"));

        if (lower.contains("return") || lower.contains("refund") || lower.contains("replace")) {
            response.setReply(
                    "Returns are easy. You can request a return from My Orders within 7 days of delivery if the item is unused and in original packaging. Refunds go back to the original payment method in 5–7 business days. COD orders are refunded to your UPI or bank account.");
            return response;
        }
        if (lower.contains("deliver") || lower.contains("shipping") || lower.contains("track")) {
            response.setReply(
                    "Standard delivery takes about 4–6 business days after the order is confirmed. You'll get an email confirmation as soon as the order is placed, and you can follow live status from My Orders. Delivery is a flat ₹99 at checkout.");
            return response;
        }
        if (lower.contains("pay") || lower.contains("cod") || lower.contains("razorpay") || lower.contains("upi")
                || lower.contains("card")) {
            response.setReply(
                    "You can pay with Card, UPI, Net Banking, or Cash on Delivery. Online payments are processed securely with Razorpay. After a successful payment you'll receive an order confirmation email with item details and the estimated delivery date.");
            return response;
        }
        if (lower.contains("order") || lower.contains("cancel")) {
            response.setReply(
                    "Open My Orders to view status, items, and totals. If an order is still pending or confirmed and not shipped, contact support from the order page to request a cancellation. You'll also get a confirmation email when the order is placed.");
            return response;
        }
        if (lower.contains("wishlist")) {
            response.setReply(
                    "Tap the heart on any product to save it to your Wishlist. You can add those items to cart later from the Wishlist page after you sign in.");
            return response;
        }

        response.setReply(
                "I can help you shop like this:\n• Search — “show me laptops”\n• Budget — “headphones under 5000”\n• Compare — “compare Sony TVs” or “laptop vs tablet”\n• Support — delivery, returns, payments, and orders.\nWhat would you like to do?");
        return response;
    }

    private AssistantChatResponse compareResponse(String message, ConversationState state,
            AssistantChatResponse response) {
        List<Product> catalog = loadCatalog();
        List<ProductType> types = detectProductTypes(message);
        if (types.isEmpty() && state.lastType != null) {
            types = List.of(state.lastType);
        }

        List<Product> picks;
        if (types.size() >= 2 && isExplicitVersus(message)) {
            picks = new ArrayList<>();
            for (ProductType type : types) {
                bestOfType(message, catalog, type).ifPresent(picks::add);
            }
        } else if (isCompareFollowUp(message.toLowerCase(Locale.ROOT)) && !state.lastProductIds.isEmpty()) {
            picks = catalog.stream()
                    .filter(product -> state.lastProductIds.contains(product.getId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            picks = sameFamily(picks);
        } else {
            ProductType type = types.isEmpty() ? null : types.get(0);
            List<ScoredProduct> scored = scoreProducts(message, catalog, type);
            picks = scored.stream()
                    .filter(item -> item.score >= 12)
                    .filter(item -> type == null || matchesType(item.product, type, message))
                    .limit(4)
                    .map(item -> item.product)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (picks.size() < 2 && type != null) {
                picks = catalog.stream()
                        .filter(product -> matchesType(product, type, message))
                        .sorted(qualityComparator(message, type).reversed())
                        .limit(3)
                        .collect(Collectors.toCollection(ArrayList::new));
            }
            picks = sameFamily(picks);
        }

        if (picks.size() < 2) {
            response.setIntent("compare");
            response.setReply(
                    "I only compare similar products — for example two TVs, two laptops, or two pairs of shoes. Try “compare Sony TVs” or “laptop vs tablet”.");
            response.setSuggestions(List.of("Compare Sony TVs", "Compare running shoes", "Compare headphones"));
            return response;
        }

        ProductType family = dominantType(picks);
        Product winner = pickWinner(message, picks, family);
        remember(state, picks, family);

        response.setIntent("compare");
        response.setProducts(picks.stream().map(this::toCard).toList());
        response.setComparisonHeaders(List.of("Feature", "Details"));
        response.setComparisonRows(buildComparison(picks));
        response.setReply("Here's a like-for-like comparison of " + joinNames(picks) + ". My pick is **"
                + winner.getName() + "** at " + inr.format(winner.getPrice())
                + " — it's the strongest match for what you asked, not a mix of unrelated items.");
        response.setSuggestions(List.of(
                "Show cheaper " + (family == null ? "options" : family.label() + "s"),
                "Best " + (family == null ? "option" : family.label()),
                "Delivery information"));
        return response;
    }

    private AssistantChatResponse searchResponse(String message, ConversationState state,
            AssistantChatResponse response) {
        List<Product> catalog = loadCatalog();
        BigDecimal budget = extractBudget(message);
        // List<ProductType> types = detectProductTypes(message);
        // ProductType type = types.isEmpty() ? state.lastType : types.get(0);
        // if (!types.isEmpty()) {
        // type = types.get(0);
        // }
        List<ProductType> types = detectProductTypes(message);

        final ProductType type = types.isEmpty()
                ? state.lastType
                : types.get(0);
        String category = categoryHint(message);
        List<ScoredProduct> scored = scoreProducts(message, catalog, type);

        List<Product> matches = scored.stream()
                .filter(item -> item.score >= 8)
                .map(item -> item.product)
                .filter(product -> type == null || matchesType(product, type, message))
                .filter(product -> category == null || type != null || category.equalsIgnoreCase(product.getCategory()))
                .filter(product -> budget == null
                        || (product.getPrice() != null && product.getPrice().compareTo(budget) <= 0))
                .limit(6)
                .collect(Collectors.toCollection(ArrayList::new));

        matches = sameFamily(matches);

        if (matches.isEmpty() && type != null) {
            matches = catalog.stream()
                    .filter(product -> matchesType(product, type, message))
                    .filter(product -> budget == null
                            || (product.getPrice() != null && product.getPrice().compareTo(budget) <= 0))
                    .sorted(qualityComparator(message, type).reversed())
                    .limit(6)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (matches.isEmpty() && type == null && category != null) {
            matches = catalog.stream()
                    .filter(product -> category.equalsIgnoreCase(product.getCategory()))
                    .sorted(Comparator.comparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo))
                            .reversed())
                    .limit(6)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (matches.isEmpty()) {
            response.setIntent("search");
            response.setReply(
                    "I couldn't find a close match for that. Try a specific product type — “Sony TVs”, “running shoes”, or “laptops under 90000”.");
            response.setSuggestions(List.of("Compare Sony TVs", "Show running shoes", "Best laptop under 90000"));
            return response;
        }

        ProductType family = type != null ? type : dominantType(matches);
        Product top = pickWinner(message, matches, family);
        remember(state, matches, family);

        String budgetText = budget == null ? "" : " within " + inr.format(budget);
        response.setIntent("search");
        response.setProducts(matches.stream().map(this::toCard).toList());
        response.setReply("I found " + matches.size() + " " + (family == null ? "match" : family.label())
                + (matches.size() == 1 ? "" : "s") + budgetText + ". **" + top.getName() + "** at "
                + inr.format(top.getPrice()) + " is the best fit — " + safe(top.getDescription()));
        response.setSuggestions(List.of(
                "Compare these " + (family == null ? "items" : family.label() + "s"),
                family == null ? "Show cheaper options" : "Cheaper " + family.label() + "s",
                "More in " + safe(matches.get(0).getCategory())));
        return response;
    }

    private List<Product> loadCatalog() {
        List<Product> products = productRepository.findAll();
        products.forEach(product -> {
            if (product.getImageName() != null
                    && (product.getImagePath() == null || product.getImagePath().isBlank())) {
                product.setImagePath(imageStorageService.getImageUrl(product.getImageName()));
            }
        });
        return products;
    }

    private List<ScoredProduct> scoreProducts(String message, List<Product> catalog, ProductType type) {
        List<String> tokens = tokenize(message);
        List<ScoredProduct> scored = new ArrayList<>();
        for (Product product : catalog) {
            int score = relevanceScore(message, tokens, product, type);
            scored.add(new ScoredProduct(product, score));
        }
        scored.sort(Comparator.comparingInt((ScoredProduct item) -> item.score).reversed()
                .thenComparing(item -> qualityRank(item.product, type), Comparator.reverseOrder()));
        return scored;
    }

    private int relevanceScore(String message, List<String> tokens, Product product, ProductType type) {
        String name = safe(product.getName()).toLowerCase(Locale.ROOT);
        String description = safe(product.getDescription()).toLowerCase(Locale.ROOT);
        String category = safe(product.getCategory()).toLowerCase(Locale.ROOT);
        int score = 0;

        if (type != null && matchesType(product, type, message)) {
            score += 20;
        } else if (type != null) {
            return 0;
        }

        for (String token : tokens) {
            if (containsWord(name, token)) {
                score += 12;
            } else if (containsWord(description, token)) {
                score += 3;
            } else if (containsWord(category, token)) {
                score += 2;
            }
        }

        if (product.getStockQuantity() != null && product.getStockQuantity() > 0) {
            score += 1;
        }
        return score;
    }

    private List<Map<String, String>> buildComparison(List<Product> products) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("Product", products.stream().map(Product::getName).toList()));
        rows.add(row("Price", products.stream().map(product -> inr.format(product.getPrice())).toList()));
        rows.add(row("Category", products.stream().map(product -> safe(product.getCategory())).toList()));
        rows.add(row("Stock",
                products.stream()
                        .map(product -> product.getStockQuantity() == null ? "—" : product.getStockQuantity() + " left")
                        .toList()));
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

    private Product pickWinner(String message, List<Product> products, ProductType type) {
        String lower = message.toLowerCase(Locale.ROOT);
        boolean wantValue = lower.contains("cheap") || lower.contains("budget") || lower.contains("under")
                || lower.contains("lowest") || lower.contains("affordable");
        List<Product> inStock = products.stream()
                .filter(product -> product.getStockQuantity() == null || product.getStockQuantity() > 0)
                .collect(Collectors.toCollection(ArrayList::new));
        List<Product> pool = inStock.isEmpty() ? products : inStock;
        if (wantValue) {
            return pool.stream()
                    .min(Comparator.comparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo)))
                    .orElse(pool.get(0));
        }
        return pool.stream()
                .max(qualityComparator(message, type))
                .orElse(pool.get(0));
    }

    private Comparator<Product> qualityComparator(String message, ProductType type) {
        return Comparator
                .comparingInt((Product product) -> qualityRank(product, type))
                .thenComparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo));
    }

    private int qualityRank(Product product, ProductType type) {
        String haystack = (safe(product.getName()) + " " + safe(product.getDescription())).toLowerCase(Locale.ROOT);
        int rank = 0;
        if (type != null && "tv".equals(type.key())) {
            if (haystack.contains("oled"))
                rank += 50;
            if (haystack.contains("65"))
                rank += 20;
            if (haystack.contains("55"))
                rank += 10;
            if (haystack.contains("4k") || haystack.contains("ultra"))
                rank += 8;
            if (haystack.contains("bravia") || haystack.contains("sony"))
                rank += 6;
        }
        if (type != null && "laptop".equals(type.key())) {
            if (haystack.contains("stand"))
                rank -= 40;
            if (haystack.contains("gaming") || haystack.contains("performance"))
                rank += 10;
        }
        if (product.getPrice() != null) {
            rank += Math.min(30, product.getPrice().intValue() / 4000);
        }
        if (product.getStockQuantity() != null && product.getStockQuantity() > 0) {
            rank += 2;
        }
        return rank;
    }

    private String bestFor(Product product) {
        BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
        String haystack = (safe(product.getName()) + " " + safe(product.getDescription())).toLowerCase(Locale.ROOT);
        if (haystack.contains("oled") || haystack.contains("flagship")
                || price.compareTo(BigDecimal.valueOf(80000)) >= 0) {
            return "Premium pick";
        }
        if (price.compareTo(BigDecimal.valueOf(2000)) < 0)
            return "Everyday value";
        return "Most shoppers";
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
        return lower
                .matches("hi|hello|hey|hola|namaste|good (morning|afternoon|evening)|what can you do|who are you.*");
    }

    private boolean looksLikeProductQuery(String lower) {
        return lower.contains("compare") || lower.contains("under") || detectProductTypes(lower).size() > 0
                || categoryHint(lower) != null;
    }

    private boolean isHelpIntent(String lower) {
        boolean orderHelp = lower.contains("order") && (lower.contains("track") || lower.contains("my")
                || lower.contains("status") || lower.contains("cancel") || lower.contains("where"));
        return lower.contains("return") || lower.contains("refund") || lower.contains("deliver")
                || lower.contains("shipping") || lower.contains("track order") || lower.contains("tracking")
                || orderHelp || lower.contains("cancel") || lower.contains("payment")
                || lower.contains("cash on delivery") || lower.contains("cod")
                || lower.contains("razorpay") || lower.contains("wishlist")
                || lower.contains("support") || lower.contains("how do returns") || lower.contains("how do i return");
    }

    private boolean isCompareIntent(String lower) {
        return lower.contains("compare") || lower.contains(" vs ") || lower.contains("versus")
                || lower.contains("difference") || lower.contains("which is better") || lower.contains("side by side");
    }

    private boolean isCompareFollowUp(String lower) {
        return lower.contains("compare these") || lower.contains("compare the top")
                || lower.equals("compare") || lower.contains("side by side");
    }

    private boolean isExplicitVersus(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(" vs ") || lower.contains("versus") || lower.contains(" or ");
    }

    private List<ProductType> detectProductTypes(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        List<ProductType> found = new ArrayList<>();
        for (ProductType type : PRODUCT_TYPES) {
            for (String keyword : type.keywords()) {
                if (matchesKeyword(lower, keyword) && !found.contains(type)) {
                    found.add(type);
                    break;
                }
            }
        }
        return found;
    }

    private boolean matchesKeyword(String text, String keyword) {
        if (keyword.contains(" ")) {
            return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
        }
        return containsWord(text, keyword);
    }

    private boolean matchesType(Product product, ProductType type, String message) {
        String haystack = (safe(product.getName()) + " " + safe(product.getDescription())).toLowerCase(Locale.ROOT);
        boolean keywordHit = type.keywords().stream().anyMatch(keyword -> matchesKeyword(haystack, keyword));
        if (!keywordHit) {
            return false;
        }
        if ("laptop".equals(type.key()) && haystack.contains("stand")
                && !message.toLowerCase(Locale.ROOT).contains("stand")) {
            return false;
        }
        if ("watch".equals(type.key()) && haystack.contains("smart")
                && !message.toLowerCase(Locale.ROOT).contains("fashion")) {
            return false;
        }
        if ("tv".equals(type.key()) && !(containsWord(haystack, "tv") || haystack.contains("television")
                || haystack.contains("oled") || haystack.contains("bravia"))) {
            return false;
        }
        return type.category().equalsIgnoreCase(product.getCategory()) || keywordHit;
    }

    private java.util.Optional<Product> bestOfType(String message, List<Product> catalog, ProductType type) {
        return catalog.stream()
                .filter(product -> matchesType(product, type, message))
                .max(qualityComparator(message, type));
    }

    private List<Product> sameFamily(List<Product> products) {
        if (products.size() <= 1) {
            return products;
        }
        ProductType family = dominantType(products);
        if (family == null) {
            String category = products.get(0).getCategory();
            return products.stream()
                    .filter(product -> category != null && category.equalsIgnoreCase(product.getCategory()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        return products.stream()
                .filter(product -> matchesType(product, family, family.label()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ProductType dominantType(List<Product> products) {
        Map<ProductType, Long> counts = new LinkedHashMap<>();
        for (Product product : products) {
            for (ProductType type : PRODUCT_TYPES) {
                if (matchesType(product, type, type.label())) {
                    counts.merge(type, 1L, Long::sum);
                    break;
                }
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private void remember(ConversationState state, List<Product> products, ProductType type) {
        state.lastProductIds = products.stream().map(Product::getId).collect(Collectors.toCollection(ArrayList::new));
        state.lastType = type;
    }

    private String categoryHint(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (!detectProductTypes(lower).isEmpty()) {
            return detectProductTypes(lower).get(0).category();
        }
        if (lower.contains("electronic") || lower.contains("gadget")) {
            return "Electronics";
        }
        if (lower.contains("fashion")) {
            return "Fashion";
        }
        if (lower.contains("home") || lower.contains("kitchen")) {
            return "Home";
        }
        if (lower.contains("sport") || lower.contains("fitness")) {
            return "Sports";
        }
        if (lower.contains("grocery") || lower.contains("groceries")) {
            return "Groceries";
        }
        if (lower.contains("toy")) {
            return "Toys";
        }
        if (lower.contains("book")) {
            return "Books";
        }
        return null;
    }

    private BigDecimal extractBudget(String message) {
        Matcher matcher = BUDGET_PATTERN.matcher(message.replace(",", ""));
        BigDecimal found = null;
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null)
                continue;
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

    private boolean containsWord(String text, String word) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(word)) {
            return false;
        }
        String escaped = Pattern.quote(word.toLowerCase(Locale.ROOT));
        return Pattern.compile("(?:^|[^a-z0-9])" + escaped + "(?:s)?(?:$|[^a-z0-9])", Pattern.CASE_INSENSITIVE)
                .matcher(text.toLowerCase(Locale.ROOT))
                .find();
    }

    private String joinNames(List<Product> products) {
        return products.stream().map(Product::getName).collect(Collectors.joining(", "));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredProduct(Product product, int score) {
    }

    private record ProductType(String key, String category, List<String> keywords) {
        String label() {
            return key.replace("-", " ");
        }
    }

    private static class ConversationState {
        private final List<String> messages = new ArrayList<>();
        private List<Long> lastProductIds = new ArrayList<>();
        private ProductType lastType;
    }
}
