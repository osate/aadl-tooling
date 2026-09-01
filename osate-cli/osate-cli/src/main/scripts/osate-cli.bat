@rem ***************************************************************************
@rem OSATE Command Line Interface
@rem
@rem Copyright 2026 Carnegie Mellon University.
@rem
@rem NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
@rem FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
@rem EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
@rem FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
@rem MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
@rem FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
@rem
@rem Licensed under a BSD (SEI)-style license, please see LICENSE.txt
@rem or contact permission@sei.cmu.edu for full terms.
@rem
@rem [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
@rem distribution.  Please see Copyright notice for non-US Government use and distribution.
@rem
@rem This Software includes and/or makes use of Third-Party Software each subject to its own license.
@rem
@rem DM26-0838
@rem ***************************************************************************
@echo off
setlocal
set DIR=%~dp0
java -jar "%DIR%..\osate-cli.jar" %*
