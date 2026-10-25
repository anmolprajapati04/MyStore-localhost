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
import java.util.LinkedHashSet;
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
            "these", "those", "them", "this", "that", "options", "items", "item", "ones", "top",
            "side", "pair", "picks", "pick", "something", "stuff", "product", "products"
    );

    private static final List<ProductType> PRODUCT_TYPES = List.of(
            new ProductType("tv", "Electronics", List.of("television", "televisions", "oled", "bravia", "smart tv", "led tv", "tvs", "tv")),
            new ProductType("laptop", "Electronics", List.of("notebook", "notebooks", "laptops", "laptop")),
            new ProductType("phone", "Electronics", List.of("smartphone", "smartphones", "mobile", "mobiles", "iphone", "phones", "phone")),
            new ProductType("headphone", "Electronics", List.of("headphones", "headphone", "headset", "earphones", "earphone", "earbuds")),
            new ProductType("smartwatch", "Electronics", List.of("smartwatch", "smart watch", "fitness tracker", "fitness band")),
            new ProductType("watch", "Fashion", List.of("chronograph", "wrist watch", "wristwatch", "watches", "watch")),
            new ProductType("camera", "Electronics", List.of("cameras", "camera", "mirrorless", "dslr", "eos")),
            new ProductType("speaker", "Electronics", List.of("speakers", "speaker", "soundbar")),
            new ProductType("tablet", "Electronics", List.of("tablets", "tablet", "ipad")),
            new ProductType("console", "Electronics", List.of("playstation", "xbox", "console", "ps5", "gaming")),
            new ProductType("keyboard", "Electronics", List.of("keyboards", "keyboard")),
            new ProductType("mouse", "Electronics", List.of("mice", "mouse")),
            new ProductType("shoes", "Fashion", List.of("running shoes", "sneakers", "sneaker", "footwear", "shoes", "shoe")),
            new ProductType("bag", "Fashion", List.of("handbags", "handbag", "bags", "bag")),
            new ProductType("dress", "Fashion", List.of("dresses", "dress")),
            new ProductType("jeans", "Fashion", List.of("denim", "jeans")),
            new ProductType("jacket", "Fashion", List.of("jackets", "jacket")),
            new ProductType("toy", "Toys", List.of("teddy", "teddy bear", "building blocks", "rc car", "remote car", "train set", "toys", "toy")),
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
            new ProductType("book", "Books", List.of("cookbook", "novel", "fiction", "science book", "books", "book")),
            new ProductType("puzzle", "Books", List.of("jigsaw", "puzzle"))
    );

    private static final List<ShoppingIntent> INTENTS = List.of(
            new ShoppingIntent("kids", "Toys",
                    List.of("kid", "kids", "child", "children", "toddler", "baby", "boy", "girl", "son", "daughter",
                            "toys", "toy", "playtime", "play"),
                    "kid-friendly picks from our Toys aisle"),
            new ShoppingIntent("gift", null,
                    List.of("gift", "gifts", "present", "presents", "birthday", "anniversary"),
                    "gift ideas from the store"),
            new ShoppingIntent("kitchen", "Home",
                    List.of("kitchen", "cooking", "cook", "recipe", "recipes"),
                    "kitchen and cooking products"),
            new ShoppingIntent("fitness", "Sports",
                    List.of("fitness", "gym", "workout", "exercise", "yoga", "sport", "sports"),
                    "sports and fitness gear"),
            new ShoppingIntent("work", "Electronics",
                    List.of("office", "study", "student", "work from home", "wfh"),
                    "work and study essentials")
    );

    private static final Pattern BUDGET_PATTERN = Pattern.compile(
            "(?:under|below|less than|within|upto|up to|max|budget of)?\\s*(?:₹|rs\\.?|inr)?\\s*(\\d{3,7})k?",
            Pattern.CASE_INSENSITIVE
    );

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
        ConversationState state = sessions.computeIfAbsent(conversationKey(request, authenticatedUser), key -> new ConversationState());
        state.messages.add(message);

        String lower = message.toLowerCase(Locale.ROOT);
        AssistantChatResponse response = new AssistantChatResponse();
        ParsedQuery query = parseQuery(message, state);

        if (isGreeting(lower) && !query.hasShoppingSignal()) {
            response.setIntent("greeting");
            response.setReply("Hi, I'm Mira. I shop from the live MyStore catalog — ask for kids' toys, a TV, running shoes, a gift under ₹2000, or help with delivery and returns.");
            response.setSuggestions(List.of("Items for kids", "Compare Sony TVs", "Gifts under 2000", "How do returns work?"));
            return response;
        }

        if (isHelpIntent(lower) && !query.hasShoppingSignal()) {
            return helpResponse(lower, response);
        }

        if (query.compare) {
            return compareResponse(query, state, response);
        }
        return searchResponse(query, state, response);
    }

    private AssistantChatResponse helpResponse(String lower, AssistantChatResponse response) {
        response.setIntent("help");
        response.setSuggestions(List.of("Track my order", "Items for kids", "Compare headphones"));

        if (lower.contains("return") || lower.contains("refund") || lower.contains("replace")) {
            response.setReply("Returns are easy. You can request a return from My Orders within 7 days of delivery if the item is unused and in original packaging. Refunds go back to the original payment method in 5–7 business days.");
            return response;
        }
        if (lower.contains("deliver") || lower.contains("shipping") || lower.contains("track")) {
            response.setReply("Standard delivery takes about 4–6 business days after the order is confirmed. You can follow live status from My Orders. Delivery is a flat ₹99 at checkout.");
            return response;
        }
        if (lower.contains("pay") || lower.contains("cod") || lower.contains("razorpay") || lower.contains("upi") || lower.contains("card")) {
            response.setReply("You can pay with Card, UPI, Net Banking, or Cash on Delivery. Online payments are processed securely with Razorpay, and you'll get an order confirmation email afterwards.");
            return response;
        }
        if (lower.contains("order") || lower.contains("cancel")) {
            response.setReply("Open My Orders to view status, items, and totals. If an order is still pending or confirmed and not shipped, contact support from the order page to request a cancellation.");
            return response;
        }
        if (lower.contains("wishlist")) {
            response.setReply("Tap the heart on any product to save it to your Wishlist. You can add those items to cart later from the Wishlist page after you sign in.");
            return response;
        }

        response.setReply("Ask me the way you would ask a store associate: “items for kids”, “compare Sony TVs”, “something for the kitchen”, or “gifts under 2000”. I only recommend products that are actually in this catalog.");
        return response;
    }

    private AssistantChatResponse compareResponse(ParsedQuery query, ConversationState state, AssistantChatResponse response) {
        List<Product> catalog = loadCatalog();
        List<Product> picks;

        if (query.types.size() >= 2 && isExplicitVersus(query.raw)) {
            picks = new ArrayList<>();
            for (ProductType type : query.types) {
                ranked(catalog, query.withType(type)).stream().map(ScoredProduct::product).findFirst().ifPresent(picks::add);
            }
        } else if (query.followUp && !state.lastProductIds.isEmpty() && query.types.isEmpty() && query.intents.isEmpty()) {
            picks = catalog.stream()
                    .filter(product -> state.lastProductIds.contains(product.getId()))
                    .collect(Collectors.toCollection(ArrayList::new));
        } else {
            picks = ranked(catalog, query).stream()
                    .filter(item -> item.score >= 10)
                    .limit(4)
                    .map(ScoredProduct::product)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        picks = keepRelated(picks, query);
        if (picks.size() < 2) {
            response.setIntent("compare");
            response.setReply("I need at least two similar products from our catalog to compare. Try “compare Sony TVs”, “compare toys”, or “laptop vs tablet”.");
            response.setSuggestions(List.of("Compare Sony TVs", "Compare toys", "Compare headphones"));
            return response;
        }

        Product winner = pickWinner(query, picks);
        remember(state, picks, query);
        response.setIntent("compare");
        response.setProducts(picks.stream().map(this::toCard).toList());
        response.setComparisonHeaders(List.of("Feature", "Details"));
        response.setComparisonRows(buildComparison(picks));
        response.setReply("From our catalog, here is a like-for-like look at " + joinNames(picks)
                + ". I'd go with **" + winner.getName() + "** at " + inr.format(winner.getPrice()) + " for what you asked.");
        response.setSuggestions(followUpSuggestions(query, picks));
        return response;
    }

    private AssistantChatResponse searchResponse(ParsedQuery query, ConversationState state, AssistantChatResponse response) {
        List<Product> catalog = loadCatalog();
        List<Product> matches = ranked(catalog, query).stream()
                .filter(item -> item.score >= 8)
                .map(ScoredProduct::product)
                .filter(product -> query.budget == null || (product.getPrice() != null && product.getPrice().compareTo(query.budget) <= 0))
                .limit(6)
                .collect(Collectors.toCollection(ArrayList::new));

        matches = keepRelated(matches, query);

        if (matches.isEmpty()) {
            response.setIntent("search");
            response.setReply("I checked the MyStore catalog and didn't find a close match. Try “items for kids”, “Sony TVs”, “running shoes”, or “gifts under 2000”.");
            response.setSuggestions(List.of("Items for kids", "Compare Sony TVs", "Gifts under 2000"));
            return response;
        }

        Product top = pickWinner(query, matches);
        remember(state, matches, query);
        response.setIntent("search");
        response.setProducts(matches.stream().map(this::toCard).toList());
        response.setReply(buildSearchReply(query, matches, top));
        response.setSuggestions(followUpSuggestions(query, matches));
        return response;
    }

    private String buildSearchReply(ParsedQuery query, List<Product> matches, Product top) {
        String why = query.intents.stream().map(intent -> intent.summary).findFirst()
                .orElse(query.types.isEmpty() ? "matching products" : query.types.get(0).label() + "s");
        String budgetText = query.budget == null ? "" : " within " + inr.format(query.budget);
        return "I looked through the MyStore catalog for " + why + budgetText + ". **" + top.getName()
                + "** at " + inr.format(top.getPrice()) + " is the strongest pick — " + safe(top.getDescription())
                + " I can compare these or show a cheaper option.";
    }

    private List<String> followUpSuggestions(ParsedQuery query, List<Product> matches) {
        String label = query.intents.stream().anyMatch(intent -> "kids".equals(intent.key))
                ? "toys"
                : (query.types.isEmpty() ? safe(matches.get(0).getCategory()).toLowerCase(Locale.ROOT) : query.types.get(0).label() + "s");
        return List.of("Compare these " + label, "Cheaper " + label, "More in " + safe(matches.get(0).getCategory()));
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

    private List<ScoredProduct> ranked(List<Product> catalog, ParsedQuery query) {
        List<ScoredProduct> scored = new ArrayList<>();
        for (Product product : catalog) {
            scored.add(new ScoredProduct(product, relevanceScore(query, product)));
        }
        scored.sort(Comparator.comparingInt((ScoredProduct item) -> item.score).reversed()
                .thenComparing(item -> qualityRank(query, item.product), Comparator.reverseOrder()));
        return scored;
    }

    private int relevanceScore(ParsedQuery query, Product product) {
        ProductProfile profile = profile(product);
        String name = safe(product.getName()).toLowerCase(Locale.ROOT);
        String description = safe(product.getDescription()).toLowerCase(Locale.ROOT);
        String category = safe(product.getCategory()).toLowerCase(Locale.ROOT);
        int score = 0;

        if (!query.types.isEmpty()) {
            boolean typeHit = query.types.stream().anyMatch(type -> matchesType(product, type, query.raw));
            if (!typeHit && query.intents.isEmpty()) {
                return 0;
            }
            if (typeHit) {
                score += 24;
            }
        }

        for (ShoppingIntent intent : query.intents) {
            if (profile.tags.contains(intent.key) || (intent.category != null && intent.category.equalsIgnoreCase(product.getCategory()))) {
                score += 28;
            }
            for (String cue : intent.cues) {
                if (containsWord(name, cue) || containsWord(description, cue) || profile.tags.contains(cue)) {
                    score += 8;
                }
            }
        }

        if (query.category != null && query.category.equalsIgnoreCase(product.getCategory())) {
            score += 16;
        }

        for (String token : query.tokens) {
            if (containsWord(name, token) || profile.tags.contains(token)) {
                score += 12;
            } else if (containsWord(description, token)) {
                score += 5;
            } else if (containsWord(category, token)) {
                score += 4;
            }
        }

        if (query.budget != null && product.getPrice() != null && product.getPrice().compareTo(query.budget) <= 0) {
            score += 4;
        }
        if (product.getStockQuantity() != null && product.getStockQuantity() > 0) {
            score += 1;
        }
        return score;
    }

    private ProductProfile profile(Product product) {
        Set<String> tags = new LinkedHashSet<>();
        String name = safe(product.getName()).toLowerCase(Locale.ROOT);
        String description = safe(product.getDescription()).toLowerCase(Locale.ROOT);
        String category = safe(product.getCategory()).toLowerCase(Locale.ROOT);
        String haystack = name + " " + description + " " + category;
        tags.add(category);

        if ("toys".equals(category) || haystack.contains("teddy") || haystack.contains("blocks")
                || haystack.contains("train") || haystack.contains("remote controlled")
                || haystack.contains("puzzle") || haystack.contains("kids")) {
            tags.addAll(List.of("kids", "child", "toy", "play", "gift"));
        }
        if (haystack.contains("tv") || haystack.contains("oled") || haystack.contains("bravia")) {
            tags.addAll(List.of("tv", "television", "electronics"));
        }
        if (haystack.contains("laptop") && !haystack.contains("stand")) {
            tags.addAll(List.of("laptop", "work", "study"));
        }
        if (haystack.contains("shoe") || haystack.contains("running")) {
            tags.addAll(List.of("shoes", "fashion", "sport"));
        }
        if (haystack.contains("air fryer") || haystack.contains("kettle") || haystack.contains("microwave")
                || haystack.contains("cookbook") || haystack.contains("kitchen")) {
            tags.addAll(List.of("kitchen", "home", "cooking"));
        }
        if (haystack.contains("yoga") || haystack.contains("dumbbell") || haystack.contains("fitness")
                || haystack.contains("cricket") || haystack.contains("football")) {
            tags.addAll(List.of("fitness", "sports"));
        }
        if (haystack.contains("biscuit") || haystack.contains("coffee") || haystack.contains("perfume")
                || haystack.contains("teddy") || haystack.contains("watch")) {
            tags.add("gift");
        }
        return new ProductProfile(tags);
    }

    private List<Product> keepRelated(List<Product> products, ParsedQuery query) {
        if (products.size() <= 1) {
            return products;
        }
        if (query.intents.stream().anyMatch(intent -> "kids".equals(intent.key))) {
            return products.stream()
                    .filter(product -> profile(product).tags.contains("kids") || "Toys".equalsIgnoreCase(product.getCategory()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        if (!query.types.isEmpty()) {
            ProductType type = query.types.get(0);
            List<Product> typed = products.stream()
                    .filter(product -> matchesType(product, type, query.raw))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (typed.size() >= 2 || (!typed.isEmpty() && !query.compare)) {
                return typed;
            }
        }
        if (query.category != null) {
            List<Product> categorized = products.stream()
                    .filter(product -> query.category.equalsIgnoreCase(product.getCategory()))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!categorized.isEmpty()) {
                return categorized;
            }
        }
        return products;
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

    private Product pickWinner(ParsedQuery query, List<Product> products) {
        boolean wantValue = query.raw.toLowerCase(Locale.ROOT).contains("cheap")
                || query.raw.toLowerCase(Locale.ROOT).contains("budget")
                || query.budget != null
                || query.raw.toLowerCase(Locale.ROOT).contains("affordable");
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
                .max(Comparator.comparingInt((Product product) -> qualityRank(query, product))
                        .thenComparing(Product::getPrice, Comparator.nullsLast(BigDecimal::compareTo)))
                .orElse(pool.get(0));
    }

    private int qualityRank(ParsedQuery query, Product product) {
        String haystack = (safe(product.getName()) + " " + safe(product.getDescription())).toLowerCase(Locale.ROOT);
        int rank = 0;
        boolean tvQuery = query.types.stream().anyMatch(type -> "tv".equals(type.key)) || haystack.contains("oled") || containsWord(query.raw, "tv");
        if (tvQuery && (haystack.contains("tv") || haystack.contains("oled"))) {
            if (haystack.contains("oled")) rank += 50;
            if (haystack.contains("65")) rank += 20;
            if (haystack.contains("55")) rank += 10;
            if (haystack.contains("4k") || haystack.contains("ultra")) rank += 8;
        }
        if (query.intents.stream().anyMatch(intent -> "kids".equals(intent.key)) && profile(product).tags.contains("kids")) {
            rank += 20;
        }
        if (haystack.contains("laptop") && haystack.contains("stand") && !query.raw.toLowerCase(Locale.ROOT).contains("stand")) {
            rank -= 40;
        }
        if (product.getPrice() != null) {
            rank += Math.min(20, product.getPrice().intValue() / 5000);
        }
        if (product.getStockQuantity() != null && product.getStockQuantity() > 0) {
            rank += 2;
        }
        return rank;
    }

    private String bestFor(Product product) {
        if (profile(product).tags.contains("kids")) return "Kids & play";
        BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
        String haystack = (safe(product.getName()) + " " + safe(product.getDescription())).toLowerCase(Locale.ROOT);
        if (haystack.contains("oled") || haystack.contains("flagship") || price.compareTo(BigDecimal.valueOf(80000)) >= 0) {
            return "Premium pick";
        }
        if (price.compareTo(BigDecimal.valueOf(2000)) < 0) return "Everyday value";
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

    private ParsedQuery parseQuery(String message, ConversationState state) {
        String lower = message.toLowerCase(Locale.ROOT);
        List<ProductType> types = detectProductTypes(lower);
        List<ShoppingIntent> intents = detectIntents(lower);
        String category = categoryHint(lower, types, intents);
        boolean followUp = isFollowUp(lower);
        boolean compare = isCompareIntent(lower) || (followUp && lower.contains("compare"));

        if (types.isEmpty() && intents.isEmpty() && category == null && followUp) {
            types = new ArrayList<>(state.lastTypes);
            intents = new ArrayList<>(state.lastIntents);
            category = state.lastCategory;
        }

        return new ParsedQuery(
                message,
                tokenize(message),
                types,
                intents,
                category,
                extractBudget(message),
                compare,
                followUp
        );
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

    private boolean isFollowUp(String lower) {
        return lower.contains("compare these") || lower.contains("compare the top") || lower.equals("compare")
                || lower.startsWith("cheaper") || lower.startsWith("more in") || lower.contains("similar")
                || lower.contains("another option");
    }

    private boolean isExplicitVersus(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(" vs ") || lower.contains("versus") || lower.contains(" or ");
    }

    private List<ProductType> detectProductTypes(String message) {
        List<ProductType> found = new ArrayList<>();
        for (ProductType type : PRODUCT_TYPES) {
            for (String keyword : type.keywords()) {
                if (matchesKeyword(message, keyword) && !found.contains(type)) {
                    found.add(type);
                    break;
                }
            }
        }
        return found;
    }

    private List<ShoppingIntent> detectIntents(String message) {
        List<ShoppingIntent> found = new ArrayList<>();
        for (ShoppingIntent intent : INTENTS) {
            for (String cue : intent.cues) {
                if (matchesKeyword(message, cue) && !found.contains(intent)) {
                    found.add(intent);
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
        if (!keywordHit && type.category().equalsIgnoreCase(product.getCategory()) && "toy".equals(type.key())) {
            return true;
        }
        if (!keywordHit) {
            return false;
        }
        if ("laptop".equals(type.key()) && haystack.contains("stand") && !message.toLowerCase(Locale.ROOT).contains("stand")) {
            return false;
        }
        if ("watch".equals(type.key()) && haystack.contains("smart") && !message.toLowerCase(Locale.ROOT).contains("fashion")) {
            return false;
        }
        if ("tv".equals(type.key()) && !(containsWord(haystack, "tv") || haystack.contains("television")
                || haystack.contains("oled") || haystack.contains("bravia"))) {
            return false;
        }
        return type.category().equalsIgnoreCase(product.getCategory()) || keywordHit;
    }

    private void remember(ConversationState state, List<Product> products, ParsedQuery query) {
        state.lastProductIds = products.stream().map(Product::getId).collect(Collectors.toCollection(ArrayList::new));
        state.lastTypes = new ArrayList<>(query.types);
        state.lastIntents = new ArrayList<>(query.intents);
        state.lastCategory = query.category;
    }

    private String categoryHint(String lower, List<ProductType> types, List<ShoppingIntent> intents) {
        if (!intents.isEmpty() && intents.get(0).category != null) {
            return intents.get(0).category;
        }
        if (!types.isEmpty()) {
            return types.get(0).category();
        }
        if (lower.contains("electronic") || lower.contains("gadget")) return "Electronics";
        if (lower.contains("fashion")) return "Fashion";
        if (lower.contains("home") || lower.contains("kitchen")) return "Home";
        if (lower.contains("sport") || lower.contains("fitness")) return "Sports";
        if (lower.contains("grocery") || lower.contains("groceries")) return "Groceries";
        if (lower.contains("toy") || lower.contains("kids") || lower.contains("children")) return "Toys";
        if (lower.contains("book")) return "Books";
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

    private record ScoredProduct(Product product, int score) {}

    private record ProductType(String key, String category, List<String> keywords) {
        String label() {
            return key.replace("-", " ");
        }
    }

    private record ShoppingIntent(String key, String category, List<String> cues, String summary) {}

    private record ProductProfile(Set<String> tags) {}

    private record ParsedQuery(
            String raw,
            List<String> tokens,
            List<ProductType> types,
            List<ShoppingIntent> intents,
            String category,
            BigDecimal budget,
            boolean compare,
            boolean followUp
    ) {
        boolean hasShoppingSignal() {
            return !types.isEmpty() || !intents.isEmpty() || category != null || budget != null
                    || compare || raw.toLowerCase(Locale.ROOT).contains("under");
        }

        ParsedQuery withType(ProductType type) {
            return new ParsedQuery(raw, tokens, List.of(type), intents, type.category(), budget, compare, followUp);
        }
    }

    private static class ConversationState {
        private final List<String> messages = new ArrayList<>();
        private List<Long> lastProductIds = new ArrayList<>();
        private List<ProductType> lastTypes = new ArrayList<>();
        private List<ShoppingIntent> lastIntents = new ArrayList<>();
        private String lastCategory;
    }
}
