package com.ecommerce.product.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AssistantChatResponse {
    private String reply;
    private String intent;
    private List<AssistantProductCard> products = new ArrayList<>();
    private List<String> comparisonHeaders = new ArrayList<>();
    private List<Map<String, String>> comparisonRows = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public List<AssistantProductCard> getProducts() { return products; }
    public void setProducts(List<AssistantProductCard> products) { this.products = products; }
    public List<String> getComparisonHeaders() { return comparisonHeaders; }
    public void setComparisonHeaders(List<String> comparisonHeaders) { this.comparisonHeaders = comparisonHeaders; }
    public List<Map<String, String>> getComparisonRows() { return comparisonRows; }
    public void setComparisonRows(List<Map<String, String>> comparisonRows) { this.comparisonRows = comparisonRows; }
    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
}
