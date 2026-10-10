package jp.cheapreview.analysis.service;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.dto.JavaAnalysis.Role;

final class SpringRoles {
  private static final Map<String, String> ANNOTATION_ROLES =
      Map.ofEntries(
          Map.entry("Controller", "Controller"),
          Map.entry("RestController", "Controller"),
          Map.entry("Service", "Service"),
          Map.entry("Repository", "Repository"),
          Map.entry("Entity", "Entity"),
          Map.entry("Configuration", "Configuration"),
          Map.entry("EnableWebSecurity", "Security"));

  private SpringRoles() {}

  /** 型自身のannotationと参照型から役割を判定する。内部型の根拠は混ぜない。 */
  static List<Role> classify(TypeDeclaration<?> type) {
    List<Role> roles = new ArrayList<>();
    type.getAnnotations()
        .forEach(
            annotation -> {
              String name = annotation.getName().getIdentifier();
              if (ANNOTATION_ROLES.containsKey(name)) {
                // Annotation names are syntax evidence, not proof of a resolved Spring dependency.
                roles.add(
                    new Role(
                        ANNOTATION_ROLES.get(name),
                        "annotation:" + annotation.getNameAsString(),
                        0.8));
              }
            });
    type.getMembers()
        .forEach(
            member -> {
              member.getAnnotations().stream()
                  .filter(a -> a.getName().getIdentifier().equals("Bean"))
                  .forEach(a -> roles.add(new Role("Configuration", "annotation:Bean", 0.8)));
            });
    // 再帰検索は内部クラスにも入るため、根拠の所属型が一致する場合だけ採用する。
    type.findAll(ClassOrInterfaceType.class).stream()
        .filter(
            reference ->
                reference
                    .findAncestor(TypeDeclaration.class)
                    .map(owner -> owner == type)
                    .orElse(false))
        .filter(t -> t.getNameAsString().equals("SecurityFilterChain"))
        .findFirst()
        .ifPresent(t -> roles.add(new Role("Security", "type:SecurityFilterChain", 0.8)));
    if (type.isRecordDeclaration()) {
      roles.add(new Role("DTO", "type:record", 1.0));
    } else if (type.getNameAsString().endsWith("Dto") || type.getNameAsString().endsWith("DTO")) {
      roles.add(new Role("DTO", "name:" + type.getNameAsString(), 0.3));
    }
    addRepositoryRoles(type, roles);

    return roles.stream().distinct().toList();
  }

  private static void addRepositoryRoles(TypeDeclaration<?> type, List<Role> roles) {
    if (type.isClassOrInterfaceDeclaration()) {
      var declaration = type.asClassOrInterfaceDeclaration();
      addRepositoryParents(declaration.getExtendedTypes(), "extends:", roles);
      addRepositoryParents(declaration.getImplementedTypes(), "implements:", roles);
    }
  }

  private static void addRepositoryParents(
      List<ClassOrInterfaceType> parents, String evidence, List<Role> roles) {
    // 外部Spring依存は取得せず、宣言された型名を構文上の根拠として保持する。
    parents.stream()
        .filter(
            t ->
                List.of(
                        "Repository",
                        "CrudRepository",
                        "JpaRepository",
                        "PagingAndSortingRepository")
                    .contains(t.getNameAsString()))
        .forEach(t -> roles.add(new Role("Repository", evidence + t.getNameAsString(), 0.8)));
  }
}
