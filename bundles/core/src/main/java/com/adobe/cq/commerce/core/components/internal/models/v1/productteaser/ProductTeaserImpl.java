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

package com.adobe.cq.commerce.core.components.internal.models.v1.productteaser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.Exporter;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.ScriptVariable;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import com.adobe.cq.commerce.core.components.client.MagentoGraphqlClient;
import com.adobe.cq.commerce.core.components.internal.datalayer.DataLayerComponent;
import com.adobe.cq.commerce.core.components.internal.datalayer.ProductDataImpl;
import com.adobe.cq.commerce.core.components.internal.models.v1.common.CommerceIdentifierImpl;
import com.adobe.cq.commerce.core.components.internal.models.v1.common.PriceImpl;
import com.adobe.cq.commerce.core.components.models.common.CommerceIdentifier;
import com.adobe.cq.commerce.core.components.models.common.Price;
import com.adobe.cq.commerce.core.components.models.productteaser.ProductTeaser;
import com.adobe.cq.commerce.core.components.models.retriever.AbstractProductRetriever;
import com.adobe.cq.commerce.core.components.services.UrlProvider;
import com.adobe.cq.commerce.core.components.services.UrlProvider.ParamsBuilder;
import com.adobe.cq.commerce.core.components.services.UrlProvider.ProductIdentifierType;
import com.adobe.cq.commerce.core.components.utils.SiteNavigation;
import com.adobe.cq.commerce.magento.graphql.ConfigurableProduct;
import com.adobe.cq.commerce.magento.graphql.ConfigurableVariant;
import com.adobe.cq.commerce.magento.graphql.ProductInterface;
import com.adobe.cq.commerce.magento.graphql.SimpleProduct;
import com.adobe.cq.commerce.magento.graphql.VirtualProduct;
import com.adobe.cq.export.json.ComponentExporter;
import com.adobe.cq.export.json.ExporterConstants;
import com.adobe.cq.wcm.core.components.models.datalayer.ComponentData;
import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Model(
    adaptables = SlingHttpServletRequest.class,
    adapters = { ProductTeaser.class, ComponentExporter.class },
    resourceType = ProductTeaserImpl.RESOURCE_TYPE)
@Exporter(
    name = ExporterConstants.SLING_MODEL_EXPORTER_NAME,
    extensions = ExporterConstants.SLING_MODEL_EXTENSION)
public class ProductTeaserImpl extends DataLayerComponent implements ProductTeaser {

    protected static final String RESOURCE_TYPE = "core/cif/components/commerce/productteaser/v1/productteaser";
    private static final String SELECTION_PROPERTY = "selection";

    @Self
    private SlingHttpServletRequest request;

    @Self(injectionStrategy = InjectionStrategy.OPTIONAL)
    private MagentoGraphqlClient magentoGraphqlClient;

    @Inject
    private Page currentPage;

    @Inject
    private UrlProvider urlProvider;

    @ScriptVariable
    private ValueMap properties;

    @ValueMapValue(
        name = "cta",
        injectionStrategy = InjectionStrategy.OPTIONAL)
    private String cta;

    @ValueMapValue(
        name = "ctaText",
        injectionStrategy = InjectionStrategy.OPTIONAL)
    private String ctaText;

    private Page productPage;
    private Pair<String, String> combinedSku;
    private AbstractProductRetriever productRetriever;

    private Locale locale;
    private Boolean isVirtualProduct;

    @PostConstruct
    protected void initModel() {
        locale = currentPage.getLanguage(false);

        productPage = SiteNavigation.getProductPage(currentPage);
        if (productPage == null) {
            productPage = currentPage;
        }
        String selection = properties.get(SELECTION_PROPERTY, String.class);
        if (selection != null && !selection.isEmpty()) {
            if (selection.startsWith("/")) {
                selection = StringUtils.substringAfterLast(selection, "/");
            }
            combinedSku = SiteNavigation.toProductSkus(selection);

            // Fetch product data
            if (magentoGraphqlClient != null) {
                productRetriever = new ProductRetriever(magentoGraphqlClient);
                productRetriever.setIdentifier(ProductIdentifierType.SKU, combinedSku.getLeft());
            }
        }
    }

    @JsonIgnore
    private ProductInterface getProduct() {
        if (productRetriever == null) {
            return null;
        }

        ProductInterface baseProduct = productRetriever.fetchProduct();
        if (combinedSku.getRight() != null && baseProduct instanceof ConfigurableProduct) {
            ConfigurableProduct configurableProduct = (ConfigurableProduct) baseProduct;
            SimpleProduct variant = findVariant(configurableProduct, combinedSku.getRight());
            if (variant != null) {
                return variant;
            }
        }
        return baseProduct;
    }

    @Override
    public CommerceIdentifier getCommerceIdentifier() {
        if (getSku() != null) {
            return CommerceIdentifierImpl.fromProductSku(getSku());
        }
        return null;
    }

    @Override
    public String getName() {
        if (getProduct() != null) {
            return getProduct().getName();
        }
        return null;
    }

    @Override
    @JsonIgnore
    public String getSku() {
        ProductInterface product = getProduct();
        String sku = product != null ? product.getSku() : null;
        return sku != null ? sku : combinedSku != null ? combinedSku.getLeft() : null;
    }

    @Override
    public String getCallToAction() {
        return cta;
    }

    @Override
    public String getCallToActionText() {
        return ctaText;
    }

    @Override
    @JsonIgnore
    public Price getPriceRange() {
        if (getProduct() != null) {
            return new PriceImpl(getProduct().getPriceRange(), locale);
        }
        return null;
    }

    @Override
    @JsonIgnore
    public String getUrl() {
        if (getProduct() != null) {
            Map<String, String> params = new ParamsBuilder()
                .sku(combinedSku.getLeft())
                .variantSku(combinedSku.getRight())
                .urlKey(productRetriever.fetchProduct().getUrlKey()) // Get slug from base product
                .variantUrlKey(getProduct().getUrlKey())
                .map();

            return urlProvider.toProductUrl(request, productPage, params);
        }
        return null;
    }

    @Override
    @JsonIgnore
    public AbstractProductRetriever getProductRetriever() {
        return productRetriever;
    }

    @Override
    @JsonIgnore
    public String getImage() {
        if (getProduct() != null) {
            return getProduct().getImage().getUrl();
        }
        return null;
    }

    @Override
    public Boolean isVirtualProduct() {
        if (isVirtualProduct == null) {
            isVirtualProduct = getProduct() instanceof VirtualProduct;
        }
        return isVirtualProduct;
    }

    private SimpleProduct findVariant(ConfigurableProduct configurableProduct, String variantSku) {
        List<ConfigurableVariant> variants = configurableProduct.getVariants();
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        return variants.stream().map(v -> v.getProduct()).filter(sp -> variantSku.equals(sp.getSku())).findFirst().orElse(null);
    }

    @Override
    public String getExportedType() {
        return RESOURCE_TYPE;
    }

    // DataLayer methods

    @Override
    protected ComponentData getComponentData() {
        return new ProductDataImpl(this, resource);
    }

    @Override
    public String getDataLayerTitle() {
        return this.getName();
    }

    @Override
    public String getDataLayerSKU() {
        return this.getSku();
    }

    @Override
    public Double getDataLayerPrice() {
        return getPriceRange() != null ? getPriceRange().getFinalPrice() : null;
    }

    @Override
    public String getDataLayerCurrency() {
        return getPriceRange() != null ? getPriceRange().getCurrency() : null;
    }
}
