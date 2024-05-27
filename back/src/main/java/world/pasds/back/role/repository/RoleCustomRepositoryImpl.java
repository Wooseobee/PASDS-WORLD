package world.pasds.back.role.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import world.pasds.back.role.entity.QRole;
import world.pasds.back.role.entity.QRoleAuthority;
import world.pasds.back.role.entity.Role;
import world.pasds.back.team.entity.Team;

import java.util.List;

@RequiredArgsConstructor
public class RoleCustomRepositoryImpl implements RoleCustomRepository {

    private final JPAQueryFactory jpaQueryFactory;

    @Override
    public List<Role> findAllByTeamWithAuthorities(Team team) {
        QRole role = QRole.role;
        QRoleAuthority roleAuthority = QRoleAuthority.roleAuthority;

        return jpaQueryFactory.selectFrom(role)
                .leftJoin(role.roleAuthorities, roleAuthority).fetchJoin()
                .leftJoin(roleAuthority.authority).fetchJoin()
                .where(role.team.eq(team))
                .distinct()
                .fetch();
    }
}
