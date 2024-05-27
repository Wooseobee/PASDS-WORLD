package world.pasds.back.role.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import world.pasds.back.role.entity.Role;
import world.pasds.back.team.entity.Team;

import java.util.List;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long>, RoleCustomRepository {

    List<Role> findAllByTeam(Team team);

    boolean existsByTeamAndName(Team team, String name);

    Role findByTeamAndName(Team team, String name);
}
