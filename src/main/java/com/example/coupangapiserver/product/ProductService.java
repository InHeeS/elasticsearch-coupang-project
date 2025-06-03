package com.example.coupangapiserver.product;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.NumberRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.example.coupangapiserver.product.domain.Product;
import com.example.coupangapiserver.product.domain.ProductDocument;
import com.example.coupangapiserver.product.dto.CreateProductRequestDto;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final ProductDocumentRepository productDocumentRepository;
  private final ElasticsearchOperations elasticsearchOperations;

  public ProductService(ProductRepository productRepository,
      ProductDocumentRepository productDocumentRepository,
      ElasticsearchOperations elasticsearchOperations) {
    this.productRepository = productRepository;
    this.productDocumentRepository = productDocumentRepository;
    this.elasticsearchOperations = elasticsearchOperations;
  }

  public List<Product> getProducts(int page, int size) {
    Pageable pageable = PageRequest.of(page - 1, size);
    return productRepository.findAll(pageable).getContent();
  }

  public Product createProduct(CreateProductRequestDto createProductRequestDto) {
    Product product = new Product(
        createProductRequestDto.getName(),
        createProductRequestDto.getDescription(),
        createProductRequestDto.getPrice(),
        createProductRequestDto.getRating(),
        createProductRequestDto.getCategory()
    );
    Product savedProduct = productRepository.save(product);

    ProductDocument productDocument = new ProductDocument(
        savedProduct.getId().toString(),
        savedProduct.getName(),
        savedProduct.getDescription(),
        savedProduct.getPrice(),
        savedProduct.getRating(),
        savedProduct.getCategory()
    );

    productDocumentRepository.save(productDocument);

    return savedProduct;
  }

  public void deleteProduct(Long id) {
    productRepository.deleteById(id);
    productDocumentRepository.deleteById(id.toString());
  }

  public List<String> getSuggestions(String query) {
    Query multiMatchQuery = MultiMatchQuery.of(m -> m
        .query(query)
        .type(TextQueryType.BoolPrefix)
        .fields("name.auto_complete", "name.auto_complete._2gram", "name.auto_complete._3gram")
    )._toQuery();

    NativeQuery nativeQuery = NativeQuery.builder()
        .withQuery(multiMatchQuery)
        .withPageable(PageRequest.of(0, 5))
        .build();

    SearchHits<ProductDocument> searchHits = this.elasticsearchOperations.search(nativeQuery, ProductDocument.class);
    return searchHits.getSearchHits().stream()
        .map(hit -> {
          ProductDocument productDocument = hit.getContent(); // hits { _source ... }
//          System.out.println(productDocument);
          return productDocument.getName();
        }).collect(Collectors.toList());
  }

  public List<ProductDocument> searchProducts(String query, String category, double minPrice,
      double maxPrice, int page, int size) {
    Query multiMatchQuery = MultiMatchQuery.of(m -> m
            .query(query)
            .fields("name^3", "description^1", "category^2")
            .fuzziness("AUTO")
        )._toQuery();

    List<Query> filters = new ArrayList<>();
    if (category != null && !category.isEmpty()){
      Query categoryFilter = TermQuery.of(t -> t
          .field("category.raw")
          .value(category)
      )._toQuery();
      filters.add(categoryFilter);
    }

    Query priceRangeFilter = NumberRangeQuery.of(r -> r
        .field("price")
        .gte(minPrice)
        .lte(maxPrice)
    )._toRangeQuery()._toQuery();
    filters.add(priceRangeFilter);

    Query ratingShould = NumberRangeQuery.of(r -> r
        .field("rating")
        .gt(4.0)
    )._toRangeQuery()._toQuery();

    Query boolQuery = BoolQuery.of(b -> b
        .must(multiMatchQuery)
        .filter(filters)
        .should(ratingShould)
    )._toQuery();

    HighlightParameters highlightParameters = HighlightParameters.builder()
        .withPreTags("<b>")
        .withPostTags("</b>")
        .build();
    Highlight highLight = new Highlight(highlightParameters, List.of(new HighlightField("name")));
    HighlightQuery highlightQuery = new HighlightQuery(highLight, ProductDocument.class);

    NativeQuery nativeQuery = NativeQuery.builder()
        .withQuery(boolQuery)
        .withHighlightQuery(highlightQuery)
        .withPageable(PageRequest.of(page - 1, size))
        .build();

    SearchHits<ProductDocument> searchHits = this.elasticsearchOperations.search(
        nativeQuery,
        ProductDocument.class
    );

    return searchHits.getSearchHits().stream()
        .map(hit -> {
          ProductDocument productDocument = hit.getContent();
          String highlightName = hit.getHighlightField("name").get(0);
          productDocument.setName(highlightName);
          return  productDocument;
        }).collect(Collectors.toList());



  }
}
