package world.pasds.back.role.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import world.pasds.back.authority.entity.AuthorityName;
import world.pasds.back.role.entity.QRoleAuthority;
import world.pasds.back.role.entity.Role;

import java.util.List;

@RequiredArgsConstructor
public class RoleAuthorityCustomRepositoryImpl implements RoleAuthorityCustomRepository {

    private final JPAQueryFactory jpaQueryFactory;

    @Override
    public boolean checkAuthority(Role role, AuthorityName authority) {
        QRoleAuthority qRoleAuthority = QRoleAuthority.roleAuthority;
        BooleanExpression queryCondition = qRoleAuthority.role.eq(role)
                .and(qRoleAuthority.authority.name.eq(authority));

        return jpaQueryFactory.select(Expressions.constant(1))
                .from(qRoleAuthority)
                .where(queryCondition)
                .fetchFirst() != null;
    }

    @Override
    public List<AuthorityName> findAuthorityNamesByRole(Role role) {
        QRoleAuthority roleAuthority = QRoleAuthority.roleAuthority;
        return jpaQueryFactory.select(roleAuthority.authority.name)
                .from(roleAuthority)
                .where(roleAuthority.role.eq(role))
                .fetch();
    }
}
