# Why this `.mvn` directory exists

This directory marks the repository checkout as the Maven *multi-module
project root*. Maven sets `${maven.multiModuleProjectDirectory}`
to the nearest ancestor of the build that contains a `.mvn` directory (falling
back to the directory of the pom passed with `-f` if none is found).

`aadl-language-server/pom.xml` resolves the OSATE p2 repository relative to that
property:

```
file://${maven.multiModuleProjectDirectory}/osate2/releng/org.osate.build.repository/target/repository/
```

The intended target is the pinned `osate2/` submodule at the repository root.
Without this marker, `mvn -f aadl-language-server/pom.xml ...` would set the
property to the nested language-server directory and resolve the repository to
the nonexistent `aadl-language-server/osate2`, failing with:

```
Failed to load p2 repository with ID 'osate' ... No repository found
```

Keeping this `.mvn` directory makes every documented build command work
regardless of whether it is run from the repo root or with `-f`. Do not delete
it.
