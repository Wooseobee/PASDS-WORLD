package world.pasds.back.role.repository;

import world.pasds.back.authority.entity.AuthorityName;
import world.pasds.back.role.entity.Role;

import java.util.List;

public interface RoleAuthorityCustomRepository {
    boolean checkAuthority(Role role, AuthorityName authority);

    List<AuthorityName> findAuthorityNamesByRole(Role role);
}
