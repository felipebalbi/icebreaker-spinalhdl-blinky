PACKAGE  := gpio
TOP      := GpioControllerDemo
SIM_MAIN := GpioControllerSim
SRC_DIR  := src
GEN_DIR  := gen
SIM_DIR  := simWorkspace
PCF      := icebreaker.pcf

# IP revision -- single source of truth for the REVISION register
# at offset 0x00 of GpioController. Threaded into every sbt invocation
# that elaborates the controller (REVISION_DEFS below) and read on the
# Scala side via sys.props in `src/hw/Revision.scala` (copied from the
# I2c project, itself copied from Uart). Keep the defaults there in
# lockstep with these values.
REVISION_MAJOR := 0
REVISION_MINOR := 1
REVISION_PATCH := 0
REVISION_DEFS  := -Drevision.major=$(REVISION_MAJOR) \
                  -Drevision.minor=$(REVISION_MINOR) \
                  -Drevision.patch=$(REVISION_PATCH)

# Tools
SBT     := sbt $(REVISION_DEFS)
YOSYS   := yosys
NEXTPNR := nextpnr-ice40
ICEPACK := icepack
ICEPROG := iceprog
GTKWAVE := gtkwave

# Derived files
V    := $(GEN_DIR)/$(TOP).v
JSON := $(GEN_DIR)/$(TOP).json
ASC  := $(GEN_DIR)/$(TOP).asc
BIN  := $(GEN_DIR)/$(TOP).bin

# Hardware sources only -- sim sources don't affect Verilog generation,
# so excluding them prevents needless rebuilds when iterating on tests.
HW_SRCS := $(shell find $(SRC_DIR)/hw -name '*.scala')

# Default target
.DEFAULT_GOAL := all

all: $(BIN) ## Build flashable bitstream (gen/<TOP>.bin)

$(GEN_DIR):
	mkdir -p $(GEN_DIR)

# 1. Generate Verilog (Spinal -> Verilog)
$(V): $(HW_SRCS) | $(GEN_DIR)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

# 2. Synthesize (Verilog -> JSON)
$(JSON): $(V)
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route (JSON -> ASC)
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR) \
		--up5k          \
		--package sg48  \
		--pcf $(PCF)    \
		--json $< \
		--asc $@  \
		--freq 12

# 4. Pack (ASC -> BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

# Aggregate sim target -- per-block sim targets get added as the
# project grows. Keep this list and .PHONY in sync. The targets
# below land with the corresponding TODO.md steps; running them
# before then will fail because the *Sim entrypoints don't exist.

sim: sim-controller ## Run all simulations (lands with Step 6)

sim-controller: ## Run GpioController APB-driven simulation (lands with Step 6)
	$(SBT) -batch "runMain $(PACKAGE).GpioControllerSim"

gen-controller: | $(GEN_DIR) ## Generate GpioController.v in gen/
	$(SBT) -batch "runMain $(PACKAGE).GpioControllerVerilog"

gen-demo: | $(GEN_DIR) ## Generate GpioControllerDemo.v in gen/ (Step 7 demo top)
	$(SBT) -batch "runMain $(PACKAGE).GpioControllerDemoVerilog"

docs: | $(GEN_DIR) ## Generate IP datasheet (HTML / C / JSON / RALF / RDL) in gen/ (lands with Step 6)
	$(SBT) -batch "runMain $(PACKAGE).GpioControllerDocs"

waves: ## Open the most recent simulation VCD in gtkwave
	@vcd=$$(find $(SIM_DIR) -name '*.vcd' -printf '%T@ %p\n' 2>/dev/null \
		| sort -nr | head -1 | cut -d' ' -f2-); \
	if [ -z "$$vcd" ]; then \
		echo "no .vcd found under $(SIM_DIR)/ -- run a sim first"; \
		exit 1; \
	fi; \
	echo "opening $$vcd"; \
	$(GTKWAVE) "$$vcd" &

flash: $(BIN) ## Flash bitstream to the iCEbreaker
	$(ICEPROG) $<

clean: ## Remove generated artefacts (gen/, simWorkspace/)
	rm -rf $(GEN_DIR) $(SIM_DIR)

distclean: clean ## clean + nuke sbt build state (target/, project/target/)
	rm -rf target project/target project/project

help: ## Show this help (every target with a ## description)
	@awk 'BEGIN { FS = ":.*## "; printf "Available targets:\n\n" } \
		/^[a-zA-Z0-9_.-]+:.*## / { printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2 }' \
		$(MAKEFILE_LIST)

.PHONY: all verilog sim sim-controller gen-controller gen-demo docs waves flash clean distclean help

verilog: $(V) ## Regenerate Verilog only
