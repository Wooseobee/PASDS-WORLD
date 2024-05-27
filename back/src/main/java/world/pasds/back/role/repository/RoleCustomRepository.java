package world.pasds.back.role.repository;

import world.pasds.back.role.entity.Role;
import world.pasds.back.team.entity.Team;

import java.util.List;

public interface RoleCustomRepository {

    List<Role> findAllByTeamWithAuthorities(Team team);
}
