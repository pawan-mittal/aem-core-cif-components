/*******************************************************************************
 *
 *    Copyright 2019 Adobe. All rights reserved.
 *    This file is licensed to you under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License. You may obtain a copy
 *    of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under
 *    the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 *    OF ANY KIND, either express or implied. See the License for the specific language
 *    governing permissions and limitations under the License.
 *
 ******************************************************************************/
package com.adobe.cq.commerce.core.components.models.retriever;

import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.commerce.core.components.client.MagentoGraphqlClient;
import com.adobe.cq.commerce.core.components.services.UrlProvider;
import com.adobe.cq.commerce.core.components.services.UrlProvider.CategoryIdentifierType;
import com.adobe.cq.commerce.graphql.client.GraphqlResponse;
import com.adobe.cq.commerce.magento.graphql.CategoryFilterInput;
import com.adobe.cq.commerce.magento.graphql.CategoryInterface;
import com.adobe.cq.commerce.magento.graphql.CategoryTreeQuery;
import com.adobe.cq.commerce.magento.graphql.CategoryTreeQueryDefinition;
import com.adobe.cq.commerce.magento.graphql.FilterEqualTypeInput;
import com.adobe.cq.commerce.magento.graphql.Operations;
import com.adobe.cq.commerce.magento.graphql.ProductInterfaceQuery;
import com.adobe.cq.commerce.magento.graphql.Query;
import com.adobe.cq.commerce.magento.graphql.QueryQuery;
import com.adobe.cq.commerce.magento.graphql.QueryQuery.CategoryListArgumentsDefinition;
import com.adobe.cq.commerce.magento.graphql.gson.Error;

public abstract class AbstractCategoryRetriever extends AbstractRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCategoryRetriever.class);

    /**
     * Lambda that extends the category query.
     */
    protected Consumer<CategoryTreeQuery> categoryQueryHook;

    /**
     * Lambda that extends the product query.
     */
    protected Consumer<ProductInterfaceQuery> productQueryHook;

    /**
     * Category instance. Is only available after populate() was called.
     */
    protected CategoryInterface category;

    /**
     * Identifier of the category that should be fetched. Which kind of identifier is used is specified in {@link #categoryIdentifierType}
     */
    protected String identifier;

    /**
     * The type of the product identifier.
     */
    protected CategoryIdentifierType categoryIdentifierType;

    /**
     * Current page for pagination of products in a category.
     */
    protected int currentPage = 1;

    /**
     * Page size for pagination of products in a category.
     */
    protected int pageSize = 6;

    /**
     * Generates the filter for a category or categoryList query
     *
     * @param identifiersFilter The filter portion to be used by CategoryFilterInput.
     * @param categoryIdentifierType The category identifiers used byt the identifiersFilter
     */
    public static CategoryFilterInput generateCategoryFilter(FilterEqualTypeInput identifiersFilter,
        UrlProvider.CategoryIdentifierType categoryIdentifierType) {
        CategoryFilterInput filter;

        if (categoryIdentifierType == null) {
            LOGGER.error("Category identifier type is not set. Falling back to ID based categoryList query");
            return new CategoryFilterInput().setCategoryUid(identifiersFilter);
        }

        switch (categoryIdentifierType) {
            case UID:
                filter = new CategoryFilterInput().setCategoryUid(identifiersFilter);
                break;
            case URL_PATH:
                filter = new CategoryFilterInput().setUrlPath(identifiersFilter);
                break;
            default:
                LOGGER.error("Category identifier type is not supported. Falling back to UID based categoryList query");
                filter = new CategoryFilterInput().setCategoryUid(identifiersFilter);
        }

        return filter;
    }

    public AbstractCategoryRetriever(MagentoGraphqlClient client) {
        super(client);
    }

    /**
     * Executes the GraphQL query and returns a category. For subsequent calls of this method, a cached category is returned.
     *
     * @return Category
     */
    public CategoryInterface fetchCategory() {
        if (this.category == null) {
            populate();
        }
        return this.category;
    }

    /**
     * Sets the current page for product pagination.
     *
     * @param currentPage The current AEM page.
     */
    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    /**
     * Sets the page size for product pagination.
     *
     * @param pageSize The page size.
     */
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * Set the identifier and the identifier type of the category that should be fetched. Setting the identifier, removes any cached data.
     *
     * @param categoryIdentifierType The category identifier type.
     * @param identifier The category identifier.
     */
    public void setIdentifier(CategoryIdentifierType categoryIdentifierType, String identifier) {
        category = null;
        query = null;
        this.identifier = identifier;
        this.categoryIdentifierType = categoryIdentifierType;
    }

    /**
     * Extend the category query part of the category GraphQL query with a partial query provided by a lambda hook that sets additional
     * fields.
     *
     * Example:
     *
     * <pre>
     * {@code
     * categoryRetriever.extendCategoryQueryWith(p -> p
     *     .level());
     * }
     * </pre>
     *
     * If called multiple times, each hook will be "appended" to the previously registered hook(s).
     *
     * @param categoryQueryHook Lambda that extends the category query
     */
    public void extendCategoryQueryWith(Consumer<CategoryTreeQuery> categoryQueryHook) {
        if (this.categoryQueryHook == null) {
            this.categoryQueryHook = categoryQueryHook;
        } else {
            this.categoryQueryHook = this.categoryQueryHook.andThen(categoryQueryHook);
        }
    }

    /**
     * @return The extended category query part if it was set with {@link AbstractCategoryRetriever#extendCategoryQueryWith(Consumer)}
     */
    public Consumer<CategoryTreeQuery> getCategoryQueryHook() {
        return categoryQueryHook;
    }

    /**
     * Extend the product query part of the category GraphQL query with a partial query provided by a lambda hook that sets additional
     * fields.
     *
     * Example:
     *
     * <pre>
     * {@code
     * categoryRetriever.extendProductQueryWith(p -> p
     *     .createdAt()
     *     .addCustomSimpleField("is_returnable"));
     * }
     * </pre>
     *
     * If called multiple times, each hook will be "appended" to the previously registered hook(s).
     *
     * @param productQueryHook Lambda that extends the product query
     */
    public void extendProductQueryWith(Consumer<ProductInterfaceQuery> productQueryHook) {
        if (this.productQueryHook == null) {
            this.productQueryHook = productQueryHook;
        } else {
            this.productQueryHook = this.productQueryHook.andThen(productQueryHook);
        }
    }

    /**
     * @return The extended product query part if it was set with {@link AbstractCategoryRetriever#extendProductQueryWith(Consumer)}
     */
    public Consumer<ProductInterfaceQuery> getProductQueryHook() {
        return productQueryHook;
    }

    /**
     * Generates the partial CategoryTree query part of the GraphQL category query.
     *
     * @return CategoryTree query definition
     */
    abstract protected CategoryTreeQueryDefinition generateCategoryQuery();

    /**
     * Generates a complete category GraphQL query with a selection of the given category identifier.
     *
     * @param identifier Category identifier, usually the category id
     * @return GraphQL query as string
     */
    public String generateQuery(String identifier) {
        CategoryTreeQueryDefinition queryArgs = generateCategoryQuery();
        return Operations.query(query -> {
            FilterEqualTypeInput identifiersFilter = new FilterEqualTypeInput().setEq(identifier);
            CategoryFilterInput filter = generateCategoryFilter(identifiersFilter, categoryIdentifierType);
            QueryQuery.CategoryListArgumentsDefinition searchArgs = s -> s.filters(filter);
            query.categoryList(searchArgs, queryArgs);
        }).toString();
    }

    /**
     * Generates a pair of args for the category query for a given category identifier;
     *
     * @param identifier Category identifier, usually the category id
     * @return GraphQL query as string
     */
    public Pair<CategoryListArgumentsDefinition, CategoryTreeQueryDefinition> generateCategoryQueryArgs(String identifier) {
        FilterEqualTypeInput identifierFilter = new FilterEqualTypeInput().setEq(identifier.replaceAll("_", "/"));
        CategoryFilterInput filter = generateCategoryFilter(identifierFilter, categoryIdentifierType);

        CategoryListArgumentsDefinition searchArgs = q -> q.filters(filter);
        CategoryTreeQueryDefinition queryArgs = generateCategoryQuery();

        return new ImmutablePair<>(searchArgs, queryArgs);
    }

    /**
     * Generates a pair of args for the category query for the instance identifier;
     *
     * @return GraphQL query as string
     */
    public Pair<CategoryListArgumentsDefinition, CategoryTreeQueryDefinition> generateCategoryQueryArgs() {
        return generateCategoryQueryArgs(identifier);
    }

    /**
     * Execute the GraphQL query with the GraphQL client.
     *
     * @return GraphqlResponse object
     */
    protected GraphqlResponse<Query, Error> executeQuery() {
        if (query == null) {
            setQuery(generateQuery(identifier));
        }
        return client.execute(query);
    }

    @Override
    protected void populate() {
        GraphqlResponse<Query, Error> response = executeQuery();
        Query rootQuery = response.getData();
        if (rootQuery.getCategoryList() != null && !rootQuery.getCategoryList().isEmpty()) {
            category = rootQuery.getCategoryList().get(0);
        }
    }
}
