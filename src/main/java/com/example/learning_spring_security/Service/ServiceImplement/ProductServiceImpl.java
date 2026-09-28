package com.example.learning_spring_security.Service.ServiceImplement;

import com.example.learning_spring_security.Constant.Constant;
import com.example.learning_spring_security.Exception.ExceptionService.ResourceNotFoundException;
import com.example.learning_spring_security.Model.Image;
import com.example.learning_spring_security.Model.Product;
import com.example.learning_spring_security.Model.ProductSku;
import com.example.learning_spring_security.Model.SubCategory;
import com.example.learning_spring_security.Repository.*;
import com.example.learning_spring_security.Service.ServiceStructure.ImageService;
import com.example.learning_spring_security.Service.ServiceStructure.ProductService;
import com.example.learning_spring_security.Service.ServiceStructure.ProductSkuService;
import com.example.learning_spring_security.ServiceMapper.ProductMapper;
import com.example.learning_spring_security.ServiceMapper.ProductSkuMapper;
import com.example.learning_spring_security.dto.Request.GetProductRequest;
import com.example.learning_spring_security.dto.Request.ProductRequest;
import com.example.learning_spring_security.dto.Request.ProductSkuRequest;
import com.example.learning_spring_security.dto.Response.ProductPageResponse;
import com.example.learning_spring_security.dto.Response.ProductResponse;
import com.example.learning_spring_security.dto.Response.ResponseErrorTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.example.learning_spring_security.Service.FirebaseNotificationService;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final ImageService imageService;
    private final ProductMapper productMapper;
    private final ProductSkuServiceImpl productSkuService;
    private final FirebaseNotificationService firebaseNotificationService;

    @Override
@Transactional
public ResponseErrorTemplate createProduct(
        ProductRequest request,
        List<MultipartFile> files,
        List<MultipartFile> skuImages
) throws Exception {

    // =========================
    // 1. FIND SUB CATEGORY
    // =========================
    SubCategory subCategory = subCategoryRepository
            .findById(request.getSubCategoryId())
            .orElseThrow(() ->
                    new ResourceNotFoundException(
                            "SubCategory not found with id: "
                                    + request.getSubCategoryId()
                    )
            );

    // =========================
    // 2. VALIDATE IMAGES
    // =========================
    if (files == null || files.isEmpty()) {
        throw new Exception("file in image is empty");
    }

    // =========================
    // 3. UPLOAD PRODUCT IMAGES
    // =========================
    List<Image> imageUrls = files.stream()
            .map(imageService::uploadImage)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    // =========================
    // 4. CREATE PRODUCT ENTITY
    // =========================
    Product product = ProductMapper.toEntity(
            request,
            subCategory
    );

    product.setImage(imageUrls);

    imageUrls.forEach(image ->
            image.setProduct(product)
    );

    // =========================
    // 5. SAVE PRODUCT
    // =========================
    Product savedProduct =
            productRepository.save(product);

    // =========================
    // 6. CREATE PRODUCT SKUS
    // =========================
    if (request.getSkus() != null
            && !request.getSkus().isEmpty()) {

        List<ProductSkuRequest> skus =
                request.getSkus();

        for (int i = 0; i < skus.size(); i++) {

            MultipartFile skuImage =
                    skuImages != null && i < skuImages.size()
                            ? skuImages.get(i)
                            : null;

            productSkuService.createSku(
                    savedProduct.getId(),
                    skus.get(i),
                    skuImage
            );
        }
    }

    log.info(
            "Product created: id={}, name={}, skus={}",
            savedProduct.getId(),
            savedProduct.getName(),
            request.getSkus() != null
                    ? request.getSkus().size()
                    : 0
    );

    // =========================
    // 7. 🔥 FCM BROADCAST
    // =========================
    try {

        log.info(
                "🔥 START NEW PRODUCT FCM: productId={}, name={}",
                savedProduct.getId(),
                savedProduct.getName()
        );

        firebaseNotificationService.sendToAllUsers(
                "New Product 🛍️",
                savedProduct.getName()
                        + " is now available.",
                Map.of(
                        "type", "NEW_PRODUCT",
                        "productId",
                        savedProduct.getId().toString()
                )
        );

        log.info(
                "✅ END NEW PRODUCT FCM: productId={}, name={}",
                savedProduct.getId(),
                savedProduct.getName()
        );

    } catch (Exception e) {

        // FCM failure must NOT fail product creation
        log.warn(
                "❌ Failed to send new product FCM. productId={}, error={}",
                savedProduct.getId(),
                e.getMessage()
        );
    }

    // =========================
    // 8. RESPONSE
    // =========================
    return productMapper.toResponse(savedProduct);
}

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getProducts(GetProductRequest request) {
        log.info("getProducts: criteriaType={}, criteriaValue={}, page={}, size={}",
                request.getCriteriaType(), request.getCriteriaValue(),
                request.getPage(), request.getSize());

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                request.getPage() - 1,
                request.getSize(),
                org.springframework.data.domain.Sort.by("id").descending()
        );

        Integer type = request.getCriteriaType();
        String value = request.getCriteriaValue();

        // ── Single-item lookups (no pagination needed) ──────────────
        if (type == 5) { // by ID — returns single product
            Product product = productRepository.findByIdNotDeleted(Long.parseLong(value))
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + value));
            return ResponseErrorTemplate.success("Product retrieved successfully",
                    productMapper.toProductResponse(product));
        }

        if (type == 6) { // by ID with SKUs
            Product product = productRepository.findByIdWithSkus(Long.parseLong(value))
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + value));
            return productMapper.toResponse(product);
        }

        // ── Paginated lookups ────────────────────────────────────────
        org.springframework.data.domain.Page<Product> page;
        String successMsg;

        if (type == null || type == 0 || value == null || value.isBlank()) {
            page = productRepository.findAllNotDeleted(pageable);
            successMsg = "Retrieved all products";
        } else if (type == 1) { // by name (fuzzy)
            page = productRepository.searchProducts(value, pageable);
            successMsg = "Retrieved products by name";
        } else if (type == 2) { // by subCategoryId
            page = productRepository.findBySubCategoryId(Long.parseLong(value), pageable);
            successMsg = "Retrieved products by sub-category";
        } else if (type == 3) { // by categoryId
            page = productRepository.findByCategoryId(Long.parseLong(value), pageable);
            successMsg = "Retrieved products by category";
        } else if (type == 4) { // active only
            page = productRepository.findByIsActiveTrue(pageable);
            successMsg = "Retrieved active products";
        } else {
            page = productRepository.findAllNotDeleted(pageable);
            successMsg = "Retrieved all products";
        }

        List<ProductResponse> payload = page.getContent()
                .stream()
                .map(productMapper::toProductResponse)
                .toList();

        ProductPageResponse pageResponse = ProductPageResponse.builder()
                .payload(payload)
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .currentPage(page.getNumber() + 1)
                .pageSize(page.getSize())
                .build();

        String message = page.isEmpty() ? "No products found" : successMsg;
        return ResponseErrorTemplate.success(message, pageResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getProductById(Long id) {
        Product product = productRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseErrorTemplate getProductWithSkus(Long id) {
        Product product = productRepository.findByIdWithSkus(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getAllProducts(Pageable pageable) {
        return productRepository.findAllNotDeleted(pageable)
                .map(productMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getActiveProducts(Pageable pageable) {
        return productRepository.findByIsActiveTrue(pageable)
                .map(productMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getProductsBySubCategory(Long subCategoryId, Pageable pageable) {
        if (!subCategoryRepository.existsById(subCategoryId)) {
            throw new ResourceNotFoundException("SubCategory not found with id: " + subCategoryId);
        }
        return productRepository.findBySubCategoryId(subCategoryId, pageable)
                .map(productMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> getProductsByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable)
                .map(productMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResponseErrorTemplate> searchProducts(String keyword, Pageable pageable) {
        return productRepository.searchProducts(keyword, pageable)
                .map(productMapper::toResponse);
    }

    @Override
    @Transactional
    public ResponseErrorTemplate updateProduct(Long id, ProductRequest request, List<MultipartFile> files, List<MultipartFile> skuImages) throws Exception {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        SubCategory subCategory = product.getSubCategory();
        if (request.getSubCategoryId() != null) {
            subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found with id: " + request.getSubCategoryId()));
        }

        if (files != null && !files.isEmpty()) {

            List<Image> newImages = files.stream()
                    .map(imageService::uploadImage)
                    .filter(Objects::nonNull)
                    .toList();

            product.getImage().clear();

            for (Image image : newImages) {
                image.setProduct(product);
                product.getImage().add(image);
            }
        }
        ProductMapper.updateEntity(product, request, subCategory);
        Product updatedProduct = productRepository.save(product);

        if (request.getSkus() != null && !request.getSkus().isEmpty()) {
            List<ProductSkuRequest> skus = request.getSkus();
            for (int i = 0; i < skus.size(); i++) {
                ProductSkuRequest skuRequest = skus.get(i);
                MultipartFile skuImage = skuImages != null && i < skuImages.size() ? skuImages.get(i) : null;
                if (skuRequest.getProductSkuId() != null) {
                    productSkuService.updateSku(skuRequest.getProductSkuId(), skuRequest, skuImage);
                } else {
                    productSkuService.createSku(updatedProduct.getId(), skuRequest, skuImage);
                }
            }
        }

        log.info("Product updated: id={}, name={}", updatedProduct.getId(), updatedProduct.getName());
        return productMapper.toResponse(updatedProduct);
    }

    @Override
    public void deleteProduct(Long id) {
        Product product = productRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        product.setDeleted(true);
        productRepository.save(product);
    }

    @Override
    public ResponseErrorTemplate updateProductStatus(Long id, Boolean isActive) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        product.setIsActive(isActive);
        Product updatedProduct = productRepository.save(product);
        return productMapper.toResponse(updatedProduct);
    }
}